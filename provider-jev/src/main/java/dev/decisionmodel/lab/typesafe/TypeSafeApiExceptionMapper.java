package dev.decisionmodel.lab.typesafe;

import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public final class TypeSafeApiExceptionMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        int status = response.getStatus();
        if (status == 429 || status == 529) {
            return new JevRetryableException(status);
        }
        return new JevApiException(status);
    }
}
