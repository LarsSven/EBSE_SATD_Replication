/*!
 *  Copyright (c) 2018 by Contributors
 * \file threading_backend.cc
 * \brief Native threading backend
 */
#include <tvm/runtime/threading_backend.h>
#include <dmlc/logging.h>
#include <thread>
#include <algorithm>
#if defined(__linux__) || defined(__ANDROID__)
#include <fstream>
#else
#endif
#if defined(__linux__)
#include <sched.h>
#endif

namespace tvm {
namespace runtime {
namespace threading {

class ThreadGroup::Impl {
 public:
  Impl(int num_workers,
       std::function<void(int)> worker_callback,
       bool exclude_worker0)
      : num_workers_(num_workers) {
    CHECK_GE(num_workers, 1)
      << "Requested a non-positive number of worker threads.";
    for (int i = exclude_worker0; i < num_workers_; ++i) {
      threads_.emplace_back([worker_callback, i] { worker_callback(i); });
    }
    InitSortedOrder();
  }
  ~Impl() { Join(); }

  void Join() {
    for (auto& t : threads_) {
      if (t.joinable()) t.join();
    }
  }

  int Configure(int mode, int nthreads, bool exclude_worker0) {
    int num_workers_used = 0;
    if (mode == -1) {
      num_workers_used = little_count_;
    } else if (mode == 1) {
      num_workers_used = big_count_;
    } else {
      // use default
      num_workers_used = threading::MaxConcurrency();
    }
    // if a specific number was given, use that
    if (nthreads) {
      num_workers_used = nthreads;
    }
    const char *val = getenv("TVM_BIND_THREADS");
    if (val == nullptr || atoi(val) == 1) {
      if (static_cast<size_t>(num_workers_) <= std::thread::hardware_concurrency()) {
        SetAffinity(exclude_worker0, mode == -1);
      } else {
        LOG(WARNING)
          << "The thread affinity cannot be set when the number of workers"
          << "is larger than the number of available cores in the system.";
      }
    }
    return num_workers_used;
  }

 private:
  // bind worker threads to disjoint cores
  // if worker 0 is offloaded to master, i.e. exclude_worker0 is true,
  // the master thread is bound to core 0.
  void SetAffinity(bool exclude_worker0, bool reverse = false) {
#if defined(__ANDROID__)
#ifndef CPU_SET
#define CPU_SETSIZE 1024
#define __NCPUBITS (8 * sizeof (uint64_t))
    typedef struct {
      uint64_t __bits[CPU_SETSIZE / __NCPUBITS];
    } cpu_set_t;

#define CPU_SET(cpu, cpusetp) \
    ((cpusetp)->__bits[(cpu)/__NCPUBITS] |= (1UL << ((cpu) % __NCPUBITS)))
#define CPU_ZERO(cpusetp) \
    memset((cpusetp), 0, sizeof(cpu_set_t))
#endif
#endif
#if defined(__linux__) || defined(__ANDROID__)
    CHECK_LE(threads_.size() + exclude_worker0, sorted_order_.size());
    if (sorted_order_.size() != threads_.size()) {
      LOG(WARNING) << "setting affinity with subset of threads!";
    }

    for (unsigned i = 0; i < threads_.size(); ++i) {
      unsigned core_id;
      if (reverse) {
        core_id = sorted_order_[sorted_order_.size() - (i + exclude_worker0) - 1];
      } else {
        core_id = sorted_order_[i + exclude_worker0];
      }
      cpu_set_t cpuset;
      CPU_ZERO(&cpuset);
      CPU_SET(core_id, &cpuset);
#if defined(__ANDROID__)
      sched_setaffinity(threads_[i].native_handle(), sizeof(cpu_set_t), &cpuset);
#else
      pthread_setaffinity_np(threads_[i].native_handle(),
          sizeof(cpu_set_t), &cpuset);
#endif
    }
    if (exclude_worker0) {  // bind the master thread to core 0
      cpu_set_t cpuset;
      CPU_ZERO(&cpuset);
      if (reverse) {
        CPU_SET(sorted_order_[sorted_order_.size() - 1], &cpuset);
      } else {
        CPU_SET(sorted_order_[0], &cpuset);
      }
#if defined(__ANDROID__)
      sched_setaffinity(pthread_self(),
        sizeof(cpu_set_t), &cpuset);
#else
      pthread_setaffinity_np(pthread_self(),
        sizeof(cpu_set_t), &cpuset);
#endif
    }
#endif
  }

  void InitSortedOrder() {
    unsigned int threads = std::thread::hardware_concurrency();
    std::vector<std::pair <unsigned int, int64_t> > max_freqs;

    for (unsigned int i = 0; i < threads; ++i) {
      int64_t cur_freq = 0;
      #if defined(__linux__) || defined(__ANDROID__)
        std::ostringstream filepath;
        filepath << "/sys/devices/system/cpu/cpu"  << i << "/cpufreq/cpuinfo_max_freq";
        std::ifstream ifs(filepath.str());
        if (!ifs.fail()) {
          if (!(ifs >> cur_freq)) {
            cur_freq = -1;
          }
          ifs.close();
        }
      #else
      #endif
      max_freqs.push_back(std::make_pair(i, cur_freq));
    }

    auto fcmpbyfreq = [] (std::pair<unsigned int, int64_t> &a,
                          std::pair<unsigned int, int64_t> &b) {
      if (a.second == b.second) {
        return a.first < b.first;
      } else {
        return a.second > b.second;
      }
    };
    std::sort(max_freqs.begin(), max_freqs.end(), fcmpbyfreq);
    int64_t big_freq = max_freqs.begin()->second;
    int64_t little_freq = max_freqs.rbegin()->second;
    for (auto it = max_freqs.begin(); it != max_freqs.end(); it++) {
      sorted_order_.push_back(it->first);
      if (big_freq == it->second) {
        big_count_++;
      }
      if (big_freq != little_freq && little_freq == it->second) {
        little_count_++;
      }
    }
    if (big_count_ + little_count_ != static_cast<int>(sorted_order_.size())) {
      LOG(WARNING) << "more than two frequencies detected!";
    }
  }

  int num_workers_;
  std::vector<std::thread> threads_;
  std::vector<unsigned int> sorted_order_;
  int big_count_ = 0;
  int little_count_ = 0;
};

ThreadGroup::ThreadGroup(int num_workers,
                         std::function<void(int)> worker_callback,
                         bool exclude_worker0)
  : impl_(new ThreadGroup::Impl(num_workers, worker_callback, exclude_worker0)) {}
ThreadGroup::~ThreadGroup() { delete impl_; }
void ThreadGroup::Join() { impl_->Join(); }

int ThreadGroup::Configure(int mode, int nthreads, bool exclude_worker0) {
  return impl_->Configure(mode, nthreads, exclude_worker0);
}

void Yield() {
  std::this_thread::yield();
}

int MaxConcurrency() {
  int max_concurrency = 1;
  const char *val = getenv("TVM_NUM_THREADS");
  if (val == nullptr) {
    val = getenv("OMP_NUM_THREADS");
  }
  if (val != nullptr) {
    max_concurrency = atoi(val);
  } else {
    max_concurrency = std::thread::hardware_concurrency();
#if defined(_M_X64) || defined(__x86_64__)
    max_concurrency /= 2;  // ignore hyper-threading
#endif
  }
  return std::max(max_concurrency, 1);
}


}  // namespace threading
}  // namespace runtime
}  // namespace tvm
