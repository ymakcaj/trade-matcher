package tradeMatcher;

import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;

/**
 * Helper responsible for extracting and validating bearer tokens from HTTP contexts.
 */
public final class AuthService {
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "BEARER ";

    private final AccountManager accountManager;

    public AuthService(AccountManager accountManager) {
        this.accountManager = accountManager;
    }

    public UserAccount requireUser(Context ctx) {
        String token = extractToken(ctx);
        return accountManager.findByToken(token)
                .orElseThrow(() -> new UnauthorizedResponse("Invalid or missing API token"));
    }

    public UserAccount requireAdmin(Context ctx) {
        UserAccount account = requireUser(ctx);
        if (!account.isAdmin()) {
            throw new UnauthorizedResponse("Admin privileges required");
        }
        return account;
    }

    private static String extractToken(Context ctx) {
        String header = ctx.header(AUTH_HEADER);
        if (header == null || header.isBlank()) {
            throw new UnauthorizedResponse("Missing Authorization header");
        }
        String trimmed = header.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedResponse("Authorization header must be Bearer token");
        }
        return trimmed.substring(trimmed.indexOf(' ') + 1).trim();
    }
}
