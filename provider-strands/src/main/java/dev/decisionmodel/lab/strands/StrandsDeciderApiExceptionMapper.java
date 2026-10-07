package dev.decisionmodel.lab.strands;

import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public final class StrandsDeciderApiExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return new StrandsDeciderRetryableException(status);
        }
        return new StrandsDeciderApiException(status);
    }
}
