/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.opentracing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.contrib.tracerresolver.TracerResolver;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Component;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.StaticService;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelEvent.ExchangeSendingEvent;
import org.apache.camel.spi.CamelEvent.ExchangeSentEvent;
import org.apache.camel.spi.CamelLogger;
import org.apache.camel.spi.LogListener;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.EndpointHelper;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StringHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * To use OpenTracing with Camel then setup this {@link OpenTracingTracer} in
 * your Camel application.
 * <p/>
 * This class is implemented as both an
 * {@link org.apache.camel.spi.EventNotifier} and {@link RoutePolicy} that
 * allows to trap when Camel starts/ends an {@link Exchange} being routed using
 * the {@link RoutePolicy} and during the routing if the {@link Exchange} sends
 * messages, then we track them using the
 * {@link org.apache.camel.spi.EventNotifier}.
 */
@ManagedResource(description = "OpenTracingTracer")
public class OpenTracingTracer extends ServiceSupport implements RoutePolicyFactory, StaticService, CamelContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTracingTracer.class);
    private static final Map<String, SpanDecorator> DECORATORS = new HashMap<>();

    private final OpenTracingEventNotifier eventNotifier = new OpenTracingEventNotifier();
    private final OpenTracingLogListener logListener = new OpenTracingLogListener();
    private Tracer tracer;
    private CamelContext camelContext;
    private Set<String> excludePatterns = new HashSet<>();
    private boolean encoding;

    static {
        ServiceLoader.load(SpanDecorator.class).forEach(d -> {
            SpanDecorator existing = DECORATORS.get(d.getComponent());
            // Add span decorator if no existing decorator for the component,
            // or if derived from the existing decorator's class, allowing
            // custom decorators to be added if they extend the standard
            // decorators
            if (existing == null || existing.getClass().isInstance(d)) {
                DECORATORS.put(d.getComponent(), d);
            }
        });
    }

    public OpenTracingTracer() {
    }

    /**
     * To add a custom decorator that does not come out of the box with camel-opentracing.
     */
    public void addDecorator(SpanDecorator decorator) {
        DECORATORS.put(decorator.getComponent(), decorator);
    }

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        // ensure this opentracing tracer gets initialized when Camel starts
        init(camelContext);
        return new OpenTracingRoutePolicy(routeId);
    }

    /**
     * Registers this {@link OpenTracingTracer} on the {@link CamelContext} if
     * not already registered.
     */
    public void init(CamelContext camelContext) {
        if (!camelContext.hasService(this)) {
            try {
                // start this service eager so we init before Camel is starting up
                camelContext.addService(this, true, true);
            } catch (Exception e) {
                throw RuntimeCamelException.wrapRuntimeCamelException(e);
            }
        }
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public Set<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(Set<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    public boolean isEncoding() {
        return encoding;
    }

    public void setEncoding(boolean encoding) {
        this.encoding = encoding;
    }

    /**
     * Adds an exclude pattern that will disable tracing for Camel messages that
     * matches the pattern.
     *
     * @param pattern the pattern such as route id, endpoint url
     */
    public void addExcludePattern(String pattern) {
        excludePatterns.add(pattern);
    }

    public Tracer getTracer() {
        return tracer;
    }

    public void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doInit() throws Exception {
        ObjectHelper.notNull(camelContext, "CamelContext", this);

        camelContext.getManagementStrategy().addEventNotifier(eventNotifier);
        if (!camelContext.getRoutePolicyFactories().contains(this)) {
            camelContext.addRoutePolicyFactory(this);
        }
        camelContext.adapt(ExtendedCamelContext.class).addLogListener(logListener);

        if (tracer == null) {
            Set<Tracer> tracers = camelContext.getRegistry().findByType(Tracer.class);
            if (tracers.size() == 1) {
                tracer = tracers.iterator().next();
            }
        }

        if (tracer == null) {
            tracer = TracerResolver.resolveTracer();
        }

        if (tracer == null) {
            // No tracer is available, so setup NoopTracer
            tracer = NoopTracerFactory.create();
        }

        ServiceHelper.startService(eventNotifier);
    }

    @Override
    protected void doShutdown() throws Exception {
        // stop event notifier
        camelContext.getManagementStrategy().removeEventNotifier(eventNotifier);
        ServiceHelper.stopService(eventNotifier);

        // remove route policy
        camelContext.getRoutePolicyFactories().remove(this);
    }

    protected SpanDecorator getSpanDecorator(Endpoint endpoint) {
        SpanDecorator sd = null;

        String uri = endpoint.getEndpointUri();
        String splitURI[] = StringHelper.splitOnCharacter(uri, ":", 2);
        if (splitURI[1] != null) {
            String scheme = splitURI[0];
            sd = DECORATORS.get(scheme);
        }
        if (sd == null) {
            // okay there was no decorator found via component name (scheme), then try FQN
            if (endpoint instanceof DefaultEndpoint) {
                Component comp = ((DefaultEndpoint) endpoint).getComponent();
                String fqn = comp.getClass().getName();
                // lookup via FQN
                sd = DECORATORS.values().stream().filter(d -> fqn.equals(d.getComponentClassName())).findFirst().orElse(null);
            }
        }
        if (sd == null) {
            sd = SpanDecorator.DEFAULT;
        }

        return sd;
    }

    private boolean isExcluded(Exchange exchange, Endpoint endpoint) {
        String url = endpoint.getEndpointUri();
        if (url != null && !excludePatterns.isEmpty()) {
            for (String pattern : excludePatterns) {
                if (EndpointHelper.matchEndpoint(exchange.getContext(), url, pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final class OpenTracingEventNotifier extends EventNotifierSupport {

        @Override
        public void notify(CamelEvent event) throws Exception {
            try {
                if (event instanceof ExchangeSendingEvent) {
                    ExchangeSendingEvent ese = (ExchangeSendingEvent)event;
                    SpanDecorator sd = getSpanDecorator(ese.getEndpoint());
                    if (!sd.newSpan() || isExcluded(ese.getExchange(), ese.getEndpoint())) {
                        return;
                    }
                    Span parent = ActiveSpanManager.getSpan(ese.getExchange());
                    SpanBuilder spanBuilder = tracer.buildSpan(sd.getOperationName(ese.getExchange(), ese.getEndpoint())).withTag(Tags.SPAN_KIND.getKey(),
                                                                                                                                  sd.getInitiatorSpanKind());
                    // Temporary workaround to avoid adding 'null' span as a
                    // parent
                    if (parent != null) {
                        spanBuilder.asChildOf(parent);
                    }
                    Span span = spanBuilder.start();
                    sd.pre(span, ese.getExchange(), ese.getEndpoint());
                    tracer.inject(span.context(), Format.Builtin.TEXT_MAP, sd.getInjectAdapter(ese.getExchange().getIn().getHeaders(), encoding));
                    ActiveSpanManager.activate(ese.getExchange(), span);
                    ActiveScopeManager.activate(ese.getExchange(), span, tracer);

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("OpenTracing: start client span={}", span);
                    }
                } else if (event instanceof ExchangeSentEvent) {
                    ExchangeSentEvent ese = (ExchangeSentEvent)event;
                    SpanDecorator sd = getSpanDecorator(ese.getEndpoint());
                    if (!sd.newSpan() || isExcluded(ese.getExchange(), ese.getEndpoint())) {
                        return;
                    }
                    Span span = ActiveSpanManager.getSpan(ese.getExchange());
                    if (span != null) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("OpenTracing: start client span={}", span);
                        }
                        sd.post(span, ese.getExchange(), ese.getEndpoint());
                        span.finish();
                        ActiveSpanManager.deactivate(ese.getExchange());
                        ActiveScopeManager.deactivate(ese.getExchange());
                    } else {
                        LOG.warn("OpenTracing: could not find managed span for exchange={}", ese.getExchange());
                    }
                }
            } catch (Throwable t) {
                // This exception is ignored
                LOG.warn("OpenTracing: Failed to capture tracing data", t);
            }
        }

        @Override
        public boolean isEnabled(CamelEvent event) {
            return event instanceof ExchangeSendingEvent || event instanceof ExchangeSentEvent;
        }

        @Override
        public String toString() {
            return "OpenTracingEventNotifier";
        }
    }

    private final class OpenTracingRoutePolicy extends RoutePolicySupport {

        OpenTracingRoutePolicy(String routeId) {
        }

        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            try {
                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                Span span = tracer.buildSpan(sd.getOperationName(exchange, route.getEndpoint()))
                    .asChildOf(tracer.extract(Format.Builtin.TEXT_MAP, sd.getExtractAdapter(exchange.getIn().getHeaders(), encoding)))
                    .withTag(Tags.SPAN_KIND.getKey(), sd.getReceiverSpanKind()).start();
                sd.pre(span, exchange, route.getEndpoint());
                ActiveSpanManager.activate(exchange, span);
                ActiveScopeManager.activate(exchange, span, tracer);
                if (LOG.isTraceEnabled()) {
                    LOG.trace("OpenTracing: start server span={}", span);
                }
            } catch (Throwable t) {
                // This exception is ignored
                LOG.warn("OpenTracing: Failed to capture tracing data", t);
            }
        }

        @Override
        public void onExchangeDone(Route route, Exchange exchange) {
            try {
                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                Span span = ActiveSpanManager.getSpan(exchange);
                if (span != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("OpenTracing: finish server span={}", span);
                    }
                    SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                    sd.post(span, exchange, route.getEndpoint());
                    span.finish();
                    ActiveSpanManager.deactivate(exchange);
                    ActiveScopeManager.deactivate(exchange);
                } else {
                    LOG.warn("OpenTracing: could not find managed span for exchange={}", exchange);
                }
            } catch (Throwable t) {
                // This exception is ignored
                LOG.warn("OpenTracing: Failed to capture tracing data", t);
            }
        }
    }

    private final class OpenTracingLogListener implements LogListener {

        @Override
        public String onLog(Exchange exchange, CamelLogger camelLogger, String message) {
            try {
                Span span = ActiveSpanManager.getSpan(exchange);
                if (span != null) {
                    Map<String, Object> fields = new HashMap<>();
                    fields.put("message", message);
                    span.log(fields);
                }
            } catch (Throwable t) {
                // This exception is ignored
                LOG.warn("OpenTracing: Failed to capture tracing data", t);
            }
            return message;
        }
    }
}
