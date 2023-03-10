# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""
Base executor - this is the base class for all the implemented executors.
"""
from collections import OrderedDict
from typing import Any, Dict, List, Optional, Set, Tuple, Union

from airflow import LoggingMixin, conf
from airflow.models import TaskInstance
from airflow.models.queue_task_run import TaskExecutionRequest
from airflow.models.taskinstance import SimpleTaskInstance, TaskInstanceKeyType
from airflow.stats import Stats
from airflow.utils.dag_processing import SimpleDag
from airflow.utils.state import State

PARALLELISM: int = conf.getint('core', 'PARALLELISM')

NOT_STARTED_MESSAGE = "The executor should be started first!"

# Task that is queued. It contains all the information that is
# needed to run the task.
#
# Tuple of: task_execution_request, priority, queue name, SimpleTaskInstance
QueuedTaskInstanceType = Tuple[
    TaskExecutionRequest, int, Optional[str], Union[SimpleTaskInstance, TaskInstance]
]


class BaseExecutor(LoggingMixin):
    """
    Class to derive in order to interface with executor-type systems
    like Celery, Kubernetes, Local, Sequential and the likes.

    :param parallelism: how many jobs should run at one time. Set to
        ``0`` for infinity
    """
    def __init__(self, parallelism: int = PARALLELISM):
        super().__init__()
        self.parallelism: int = parallelism
        self.queued_tasks: OrderedDict[TaskInstanceKeyType, QueuedTaskInstanceType] \
            = OrderedDict()
        self.running: Set[TaskInstanceKeyType] = set()
        self.event_buffer: Dict[TaskInstanceKeyType, Optional[str]] = {}

    def start(self):  # pragma: no cover
        """
        Executors may need to get things started.
        """

    def _queue_task_execution_request(
        self,
        simple_task_instance: SimpleTaskInstance,
        task_execution_request: TaskExecutionRequest,
        priority: int = 1,
        queue: Optional[str] = None
    ):
        """Queues task exeuction request run of local task job to task"""
        if simple_task_instance.key not in self.queued_tasks and simple_task_instance.key not in self.running:
            self.log.info("Adding to queue: %s", task_execution_request)
            self.queued_tasks[simple_task_instance.key] = (
                task_execution_request, priority, queue, simple_task_instance
            )
        else:
            self.log.info("could not queue task %s", simple_task_instance.key)

    def queue_task_instance(
            self,
            task_instance: TaskInstance,
            mark_success: bool = False,
            pickle_id: Optional[str] = None,
            ignore_all_deps: bool = False,
            ignore_depends_on_past: bool = False,
            ignore_task_deps: bool = False,
            ignore_ti_state: bool = False,
            pool: Optional[str] = None,
            cfg_path: Optional[str] = None) -> None:
        """Queues task instance."""
        pool = pool or task_instance.pool

        # TODO (edgarRd): AIRFLOW-1985:
        # cfg_path is needed to propagate the config values if using impersonation
        # (run_as_user), given that there are different code paths running tasks.
        # For a long term solution we need to address AIRFLOW-1986
        task_execution_request = TaskExecutionRequest(
            dag_id=task_instance.dag_id,
            task_id=task_instance.task_id,
            execution_date=task_instance.execution_date,
            mark_success=mark_success,
            ignore_all_dependencies=ignore_all_deps,
            ignore_depends_on_past=ignore_depends_on_past,
            ignore_dependencies=ignore_task_deps,
            force=ignore_ti_state,
            pool=pool,
            pickle_id=pickle_id,
            cfg_path=cfg_path,
        )
        self._queue_task_execution_request(
            SimpleTaskInstance(task_instance),
            task_execution_request,
            priority=task_instance.task.priority_weight_total,
            queue=task_instance.task.queue)

    def queue_simple_task_instance(self, simple_task_instance: SimpleTaskInstance, simple_dag: SimpleDag):
        """Queues simple task instance."""
        priority = simple_task_instance.priority_weight
        queue = simple_task_instance.queue

        task_execution_request = TaskExecutionRequest(
            dag_id=simple_task_instance.dag_id,
            task_id=simple_task_instance.task_id,
            execution_date=simple_task_instance.execution_date,
            pool=simple_task_instance.pool,
            subdir=simple_dag.full_filepath,
            pickle_id=simple_dag.pickle_id
        )

        self._queue_task_execution_request(
            simple_task_instance,
            task_execution_request,
            priority=priority,
            queue=queue
        )

    def has_task(self, task_instance: TaskInstance) -> bool:
        """
        Checks if a task is either queued or running in this executor.

        :param task_instance: TaskInstance
        :return: True if the task is known to this executor
        """
        return task_instance.key in self.queued_tasks or task_instance.key in self.running

    def sync(self) -> None:
        """
        Sync will get called periodically by the heartbeat method.
        Executors should override this to perform gather statuses.
        """

    def heartbeat(self) -> None:
        """
        Heartbeat sent to trigger new jobs.
        """
        if not self.parallelism:
            open_slots = len(self.queued_tasks)
        else:
            open_slots = self.parallelism - len(self.running)

        num_running_tasks = len(self.running)
        num_queued_tasks = len(self.queued_tasks)

        self.log.debug("%s running task instances", num_running_tasks)
        self.log.debug("%s in queue", num_queued_tasks)
        self.log.debug("%s open slots", open_slots)

        Stats.gauge('executor.open_slots', open_slots)
        Stats.gauge('executor.queued_tasks', num_queued_tasks)
        Stats.gauge('executor.running_tasks', num_running_tasks)

        self.trigger_tasks(open_slots)

        # Calling child class sync method
        self.log.debug("Calling the %s sync method", self.__class__)
        self.sync()

    def order_queued_tasks_by_priority(self) -> List[Tuple[TaskInstanceKeyType, QueuedTaskInstanceType]]:
        """
        Orders the queued tasks by priority.

        :return: List of tuples from the queued_tasks according to the priority.
        """
        return sorted(
            [(k, v) for k, v in self.queued_tasks.items()],  # pylint: disable=unnecessary-comprehension
            key=lambda x: x[1][1],
            reverse=True)

    def trigger_tasks(self, open_slots: int) -> None:
        """
        Triggers tasks

        :param open_slots: Number of open slots
        """
        sorted_queue = self.order_queued_tasks_by_priority()

        for _ in range(min((open_slots, len(self.queued_tasks)))):
            key, (task_execution_request, _, _, simple_ti) = sorted_queue.pop(0)
            self.queued_tasks.pop(key)
            self.running.add(key)
            self.execute_async(key=key,
                               task_execution_request=task_execution_request,
                               queue=None,
                               executor_config=simple_ti.executor_config)

    def change_state(self, key: TaskInstanceKeyType, state: str) -> None:
        """
        Changes state of the task.

        :param key: Unique key for the task instance
        :param state: State to set for the task.
        """
        self.log.debug("Changing state: %s", key)
        try:
            self.running.remove(key)
        except KeyError:
            self.log.debug('Could not find key: %s', str(key))
        self.event_buffer[key] = state

    def fail(self, key: TaskInstanceKeyType) -> None:
        """
        Set fail state for the event.

        :param key: Unique key for the task instance
        """
        self.change_state(key, State.FAILED)

    def success(self, key: TaskInstanceKeyType) -> None:
        """
        Set success state for the event.

        :param key: Unique key for the task instance
        """
        self.change_state(key, State.SUCCESS)

    def get_event_buffer(self, dag_ids=None) -> Dict[TaskInstanceKeyType, Optional[str]]:
        """
        Returns and flush the event buffer. In case dag_ids is specified
        it will only return and flush events for the given dag_ids. Otherwise
        it returns and flushes all events.

        :param dag_ids: to dag_ids to return events for, if None returns all
        :return: a dict of events
        """
        cleared_events: Dict[TaskInstanceKeyType, Optional[str]] = dict()
        if dag_ids is None:
            cleared_events = self.event_buffer
            self.event_buffer = dict()
        else:
            for key in list(self.event_buffer.keys()):
                dag_id, _, _, _ = key
                if dag_id in dag_ids:
                    cleared_events[key] = self.event_buffer.pop(key)

        return cleared_events

    def execute_async(self,
                      key: TaskInstanceKeyType,
                      task_execution_request: TaskExecutionRequest,
                      queue: Optional[str] = None,
                      executor_config: Optional[Any] = None) -> None:  # pragma: no cover
        """
        This method will execute the task execution request run asynchronously.

        :param key: Unique key for the task instance
        :param task_execution_request: Task execution request that contains information about task
        :param queue: name of the queue
        :param executor_config: Configuration passed to the executor.
        """
        raise NotImplementedError()

    def end(self) -> None:  # pragma: no cover
        """
        This method is called when the caller is done submitting job and
        wants to wait synchronously for the job submitted previously to be
        all done.
        """
        raise NotImplementedError()

    def terminate(self):
        """
        This method is called when the daemon receives a SIGTERM
        """
        raise NotImplementedError()
