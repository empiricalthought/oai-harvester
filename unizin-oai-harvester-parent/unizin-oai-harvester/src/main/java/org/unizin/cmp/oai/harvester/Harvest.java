package org.unizin.cmp.oai.harvester;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.http.client.methods.HttpUriRequest;
import org.unizin.cmp.oai.OAI2Constants;
import org.unizin.cmp.oai.OAIRequestParameter;
import org.unizin.cmp.oai.ResumptionToken;
import org.unizin.cmp.oai.harvester.HarvestNotification.HarvestNotificationType;
import org.unizin.cmp.oai.harvester.response.OAIEventHandler;
import org.unizin.cmp.oai.harvester.response.OAIResponseHandler;

/**
 * Internal-use-only mutable harvest state.
 *
 */
final class Harvest {

    static final class State {
        volatile boolean running;
        volatile boolean explicitlyStopped;
        boolean interrupted;
    }

    private final HarvestParams params;
    private final OAIResponseHandler responseHandler;
    private final State state = new State();
    private HttpUriRequest request;
    private Exception exception;
    /**
     * The resumption token from the last response, if any.
     * <p>
     * Must be {@code volatile} so that {@link #getRetryParams()} can be called
     * from multiple threads.
     * </p>
     */
    private volatile ResumptionToken resumptionToken;
    private Instant lastResponseDate;
    private long requestCount;
    private long responseCount;


    Harvest() {
        this(null, null);
    }

    Harvest(final HarvestParams params,
            final OAIResponseHandler responseHandler) {
        this.params = params;
        this.responseHandler = responseHandler;
    }

    HarvestNotification createNotification(
            final HarvestNotificationType type) {
        final Map<String, Long> stats = new HashMap<>(2);
        stats.put(HarvestNotification.Statistics.REQUEST_COUNT, requestCount);
        stats.put(HarvestNotification.Statistics.RESPONSE_COUNT,
                responseCount);
        return new HarvestNotification(type, state, exception,
                resumptionToken, lastResponseDate, params, stats);
    }

    void setLastResponseDate(final Instant lastResponseDate) {
        this.lastResponseDate = lastResponseDate;
    }

    void setResumptionToken(final ResumptionToken resumptionToken) {
        Objects.requireNonNull(resumptionToken, "resumptionToken");
        this.resumptionToken = resumptionToken;
    }

    void setRequest(final HttpUriRequest request) {
        this.request = request;
    }

    HttpUriRequest getRequest() {
        return request;
    }

    /**
     * Get the parameters for the next request.
     * @return the parameters for the next request.
     */
    Map<String, String> getRequestParameters() {
        if (resumptionToken != null) {
            final Map<String, String> m = new HashMap<>();
            m.put(OAIRequestParameter.RESUMPTION_TOKEN.paramName(),
                    resumptionToken.getToken());
            m.put(OAI2Constants.VERB_PARAM_NAME,
                    params.getVerb().localPart());
            return m;
        }
        return params.getParameters();
    }

    URI getBaseURI() {
        return params.getBaseURI();
    }

    void start() {
        state.running = true;
    }

    void stop() {
        state.running = false;
    }

    /**
     * This method should be called only from {@link Harvester#stop()}.
     */
    void requestStop() {
        state.explicitlyStopped = true;
        stop();
    }

    void error(final Exception e) {
        exception = e;
        stop();
    }

    void requestSent() {
        requestCount++;
    }

    void responseReceived() {
        responseCount++;
    }

    boolean hasNext() {
        /* On the off chance that somebody else down the line is interested
         * in the interrupted flag, we avoid clearing it.
         */
        if (Thread.currentThread().isInterrupted()) {
            state.interrupted = true;
            stop();
        }
        return state.running;
    }

    HarvestParams getRetryParams() {
        if (params != null) {
            return params.getRetryParameters(resumptionToken);
        }
        throw new IllegalStateException("No current harvest parameters.");
    }

    OAIResponseHandler getResponseHandler() {
        return responseHandler;
    }

    OAIEventHandler getEventHandler(final HarvestNotification notification) {
        return responseHandler.getEventHandler(notification);
    }
}

