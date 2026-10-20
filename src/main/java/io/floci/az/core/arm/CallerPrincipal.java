package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

/**
 * The object id ({@code oid} claim) of the bearer token on an ARM request. The token's signature is not
 * checked: ARM authentication is not enforced, so the claim is taken as the caller presents it.
 */
public final class CallerPrincipal {

    private static final Logger LOG = Logger.getLogger(CallerPrincipal.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CallerPrincipal() {
    }

    public static Optional<String> objectId(AzureRequest request) {
        if (request.headers() == null) {
            return Optional.empty();
        }
        String header = request.headers().getHeaderString("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String[] parts = header.substring(7).trim().split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode oid = claims.get("oid");
            return oid != null && oid.isTextual() ? Optional.of(oid.asText()) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugv("Bearer token carries no readable claims: {0}", e.getMessage());
            return Optional.empty();
        }
    }
}
