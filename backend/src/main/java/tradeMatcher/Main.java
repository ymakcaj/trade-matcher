package tradeMatcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Gson JSON = new Gson();
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final PriceScaleRegistry PRICE_SCALES = PriceScaleProvider.getRegistry();
    private static final String DEFAULT_TICKER = "TEST";
    private static final String ADMIN_USER_ID = "admin";
    private static final List<Map<String, Object>> INSTRUMENTS = List.of(
            Map.of("ticker", DEFAULT_TICKER, "tickSize", 0.001d, "minOrderQty", 1L),
            Map.of("ticker", "DEMO", "tickSize", 0.001d, "minOrderQty", 1L));
    private static final Map<String, String> MARKET_STATUS = Map.of("sessionStatus", "OPEN");

    public static void main(String[] args) {
        AccountManager accountManager = new AccountManager();
        List<SeedAccount> seedAccounts = loadSeedAccounts();
        if (seedAccounts.isEmpty()) {
            accountManager.registerAccount(ADMIN_USER_ID, 5_000_000d,
                    Map.of(DEFAULT_TICKER, 1_000_000L, "DEMO", 1_000_000L), true);
            accountManager.registerAccount("alpha", 250_000d, Map.of(DEFAULT_TICKER, 10_000L), false);
            accountManager.registerAccount("beta", 250_000d, Map.of(DEFAULT_TICKER, 10_000L), false);
        } else {
            for (SeedAccount seed : seedAccounts) {
                try {
                    accountManager.registerAccountWithApiKey(
                            seed.userId(),
                            seed.apiKey(),
                            seed.cash(),
                            seed.positions(),
                            seed.admin());
                } catch (IllegalArgumentException ex) {
                    LOG.warn("Failed to register seed account {}: {}", seed.userId(), ex.getMessage());
                }
            }
        }

        LOG.info("Provisioned demo accounts:");
        for (UserAccount account : accountManager.getAllAccounts()) {
            LOG.info("user={} token={}", account.getUserId(), account.getApiKey());
        }

    MatchingEngine engine = new MatchingEngine(accountManager);
    PublicFeedService publicFeed = new PublicFeedService();
    PrivateFeedService privateFeed = new PrivateFeedService();
    AuthService authService = new AuthService(accountManager);
    OrderIdGenerator orderIdGenerator = new OrderIdGenerator();

        engine.onOrderBookUpdate(levels -> publicFeed.broadcastDelta(DEFAULT_TICKER, levels));
        engine.onTrades(trades -> publicFeed.broadcastTrades(DEFAULT_TICKER, trades));
        engine.onFill(privateFeed::sendFill);

        int port = resolvePort();

        // 3. Start the Javalin server
        Javalin app = Javalin.create(config -> {
            if (Main.class.getResource("/public") != null) {
                // Serve pre-built frontend assets when they are bundled into the jar
                config.staticFiles.add("/public");
            } else {
                // Fallback for local development: point to the React build output
                Path frontendBuild = Paths.get("..", "frontend", "build").toAbsolutePath().normalize();
                if (Files.exists(frontendBuild)) {
                    config.staticFiles.add(staticFiles -> {
                        staticFiles.directory = frontendBuild.toString();
                        staticFiles.location = Location.EXTERNAL;
                    });
                }
            }
        }).start("0.0.0.0", port);

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        });

        app.options("/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.status(204);
        });

        // 4. Define the HTTP "Command" Endpoint
        // This is how the user SUBMITS an order
    app.post("/api/order", ctx -> {
            UserAccount user = authService.requireUser(ctx);
            OrderPayload payload = ctx.bodyValidator(OrderPayload.class)
                    .check(p -> p.orderType() != null && !p.orderType().isBlank(), "orderType is required")
                    .check(p -> p.side() != null && !p.side().isBlank(), "side is required")
                    .check(p -> p.quantity() != null && p.quantity() > 0, "quantity must be positive")
                    .get();

        String assignedOrderId = orderIdGenerator.nextId();
        String clientOrderId = payload.orderId();

            try {
        Order newOrder = toDomainOrder(user.getUserId(), payload, assignedOrderId);
                PriceScale scale = PRICE_SCALES.getScale(newOrder.getTicker());
                double displayPrice = scale.toDisplayPrice((int) Math.round(newOrder.GetPrice()));
                LOG.info("Received order via HTTP: type={}, side={}, price={}, qty={}, id={}",
                        newOrder.GetOrderType(), newOrder.GetSide(), displayPrice,
                        newOrder.GetInitialQuantity(), newOrder.GetOrderId());

                engine.processOrder(newOrder);
                privateFeed.sendAcknowledgement(user.getUserId(), assignedOrderId, clientOrderId);
                Map<String, Object> response = new java.util.HashMap<>();
                response.put("status", "Order received");
                response.put("orderId", assignedOrderId);
                if (clientOrderId != null && !clientOrderId.isBlank()) {
                    response.put("clientOrderId", clientOrderId);
                }
                ctx.json(response);
            } catch (IllegalArgumentException ex) {
                LOG.warn("Rejected order payload {}: {}", payload, ex.getMessage());
        privateFeed.sendReject(user.getUserId(), assignedOrderId, clientOrderId, ex.getMessage());
                Map<String, Object> errorBody = new java.util.HashMap<>();
                errorBody.put("status", "error");
                errorBody.put("message", ex.getMessage());
                errorBody.put("orderId", assignedOrderId);
                if (clientOrderId != null && !clientOrderId.isBlank()) {
                    errorBody.put("clientOrderId", clientOrderId);
                }
                ctx.status(400).json(errorBody);
            }
        });

    app.delete("/api/order/{orderId}", ctx -> {
            UserAccount user = authService.requireUser(ctx);
            long orderId;
            try {
                orderId = Long.parseLong(ctx.pathParam("orderId"));
            } catch (NumberFormatException ex) {
                ctx.status(400).json(Map.of("status", "error", "message", "INVALID_ORDER_ID"));
                return;
            }
            boolean canceled = engine.cancelOrder(user.getUserId(), orderId);
            if (canceled) {
                privateFeed.sendCanceled(user.getUserId(), Long.toString(orderId));
                ctx.json(Map.of("status", "Canceled", "orderId", orderId));
            } else {
                ctx.status(404).json(Map.of("status", "error", "message", "ORDER_NOT_FOUND"));
            }
        });

        app.get("/api/account", ctx -> {
            UserAccount user = authService.requireUser(ctx);
            List<Map<String, Object>> positions = new ArrayList<>();
            user.snapshotPositions().forEach((ticker, qty) -> positions.add(Map.of(
                    "ticker", ticker,
                    "quantity", qty)));
            ctx.json(Map.of(
                    "userId", user.getUserId(),
                    "cash", user.getCashBalance(),
                    "positions", positions));
        });

        app.get("/api/orders", ctx -> {
            UserAccount user = authService.requireUser(ctx);
            List<Map<String, Object>> orders = new ArrayList<>();
            for (OrderDetails detail : engine.getOpenOrdersForUser(user.getUserId())) {
                orders.add(Map.of(
                        "orderId", detail.getOrderId(),
                        "ticker", detail.getTicker(),
                        "side", detail.getSide().name(),
                        "orderType", detail.getOrderType().name(),
                        "price", detail.getPrice(),
                        "quantity", detail.getRemainingQuantity()));
            }
            ctx.json(orders);
        });

        app.get("/api/fills", ctx -> {
            UserAccount user = authService.requireUser(ctx);
            List<Map<String, Object>> fills = new ArrayList<>();
            for (FillRecord fill : engine.getFillsForUser(user.getUserId())) {
                fills.add(Map.of(
                        "fillId", fill.fillId(),
                        "orderId", fill.orderId(),
                        "ticker", fill.ticker(),
                        "side", fill.side().name(),
                        "price", fill.price(),
                        "quantity", fill.quantity(),
                        "timestamp", fill.timestamp().toString()));
            }
            ctx.json(fills);
        });

    app.get("/api/instruments", ctx -> ctx.json(INSTRUMENTS));
    app.get("/api/market/status", ctx -> ctx.json(MARKET_STATUS));
    app.get("/api/market/{ticker}/book", ctx -> {
        String requestedTicker = ctx.pathParam("ticker");
        if (!isSupportedTicker(requestedTicker)) {
        ctx.status(404).json(Map.of(
            "status", "error",
            "message", "UNKNOWN_TICKER"));
        return;
        }
        String normalizedTicker = normalizeTicker(requestedTicker);
        OrderbookLevelInfos snapshot = engine.getOrderbookLevels();
        ctx.json(Map.of(
            "ticker", normalizedTicker,
            "bids", snapshot.GetBids(),
            "asks", snapshot.GetAsks()));
    });

        app.post("/api/script", ctx -> {
            UserAccount admin = authService.requireAdmin(ctx);
            String rawBody = ctx.body();
            LOG.debug("Received raw script payload: {}", rawBody);

            List<String> script;
            try {
                script = parseScriptPayload(rawBody);
            } catch (IllegalArgumentException ex) {
                LOG.warn("Rejected script payload: {}", ex.getMessage());
                ctx.status(400).json(Map.of(
                        "status", "error",
                        "message", ex.getMessage()));
                return;
            }

            LOG.info("Received script with {} commands", script.size());
            int executed = 0;
            for (String command : script) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String trimmed = command.trim();
                LOG.info("Executing script line: {}", trimmed);
                try {
                    executeScriptLine(engine, admin.getUserId(), trimmed);
                    executed++;
                } catch (IllegalArgumentException ex) {
                    LOG.warn("Failed to execute script line '{}': {}", trimmed, ex.getMessage());
                    ctx.status(400).json(Map.of(
                            "status", "error",
                            "message", ex.getMessage(),
                            "line", trimmed));
                    return;
                }
            }
            ctx.json(Map.of(
                    "status", "Script executed",
                    "commandsProcessed", executed));
        });

        app.post("/api/reset", ctx -> {
            authService.requireAdmin(ctx);
            LOG.info("Received reset request");
            engine.reset();
            ctx.json(Map.of("status", "Reset complete"));
        });

        app.ws("/ws/public", ws -> {
            ws.onConnect(ctx -> {
                publicFeed.register(ctx.session);
                publicFeed.sendSnapshot(ctx.session, DEFAULT_TICKER, engine.getOrderbookLevels());
            });
            ws.onClose(ctx -> publicFeed.unregister(ctx.session));
        });

        app.ws("/ws/private", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                UserAccount account = accountManager.findByToken(token).orElse(null);
                if (account == null) {
                    ctx.session.close(4001, "Unauthorized");
                    return;
                }
                ctx.attribute("userId", account.getUserId());
                privateFeed.register(ctx.session, account.getUserId());
            });

            ws.onClose(ctx -> {
                String userId = ctx.attribute("userId");
                privateFeed.unregister(ctx.session, userId);
            });
        });
    }

    private static int resolvePort() {
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ex) {
                LOG.warn("Invalid PORT environment value '{}', falling back to 7070", envPort);
            }
        }
        return 7070;
    }

    private static void executeScriptLine(MatchingEngine engine, String userId, String command) {
        String[] tokens = command.split("\\s+");
        if (tokens.length == 0) {
            return;
        }
        char code = Character.toUpperCase(tokens[0].charAt(0));
        switch (code) {
            case 'A':
                if (tokens.length < 6) {
                    throw new IllegalArgumentException("Add command requires 6 tokens");
                }
                ParsedOrderAttributes scriptAttributes = resolveOrderAttributes(tokens[2], null);
                OrderSide scriptSide = parseSide(tokens[1]);
                long scriptQuantity = Long.parseLong(tokens[4]);
                double scriptPrice = Double.parseDouble(tokens[3]);
                String scriptOrderId = tokens[5];
                PriceScale scale = PRICE_SCALES.getScale("DEMO");
                int bookPrice = scriptAttributes.orderType() == OrderType.MARKET ? 0 : scale.toBookPrice(scriptPrice);
                int bookTrigger = bookPrice;
                engine.processOrder(new Order(
                        scriptOrderId,
                        userId,
                        "DEMO",
                        scriptSide,
                        scriptAttributes.orderType(),
                        scriptAttributes.timeInForce(),
                        scriptQuantity,
                        bookPrice,
                        bookTrigger,
                        false,
                        scriptQuantity));
                break;
            case 'M':
                if (tokens.length < 5) {
                    throw new IllegalArgumentException("Modify command requires 5 tokens");
                }
                PriceScale modifyScale = PRICE_SCALES.getScale("DEMO");
                engine.modifyOrder(
                        userId,
                        Long.parseLong(tokens[1]),
                        parseSide(tokens[2]),
                        modifyScale.toBookPrice(Double.parseDouble(tokens[3])),
                        Integer.parseInt(tokens[4]));
                break;
            case 'R':
            case 'C':
                if (tokens.length < 2) {
                    throw new IllegalArgumentException("Cancel command requires an order id");
                }
                engine.cancelOrder(userId, Long.parseLong(tokens[1]));
                break;
            default:
                throw new IllegalArgumentException("Unsupported script code '" + code + "'");
        }
    }

    private static Order toDomainOrder(String userId, OrderPayload payload, String assignedOrderId) {
        ParsedOrderAttributes attributes = resolveOrderAttributes(payload.orderType(), payload.timeInForce());
        OrderType type = attributes.orderType();
        TimeInForce timeInForce = attributes.timeInForce();
        OrderSide side = parseSide(payload.side());

        Long quantityValue = payload.quantity();
        if (quantityValue == null || quantityValue <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        long quantity = quantityValue;

        double price = payload.price() != null ? payload.price() : 0.0;
        double triggerPrice = payload.triggerPrice() != null ? payload.triggerPrice() : price;

        String ticker = payload.ticker() != null && !payload.ticker().isBlank() ? payload.ticker().toUpperCase() : DEFAULT_TICKER;
        PriceScale scale = PRICE_SCALES.getScale(ticker);

        if ((type == OrderType.LIMIT || type == OrderType.STOP_LIMIT) && price <= 0.0) {
            throw new IllegalArgumentException("Limit orders require a positive price");
        }
        if ((type == OrderType.STOP_MARKET || type == OrderType.STOP_LIMIT) && triggerPrice <= 0.0) {
            throw new IllegalArgumentException("Stop orders require a positive trigger price");
        }
        int bookPrice = type == OrderType.MARKET ? 0 : scale.toBookPrice(price);
        int bookTriggerPrice = (type == OrderType.STOP_MARKET || type == OrderType.STOP_LIMIT)
                ? scale.toBookPrice(triggerPrice)
                : bookPrice;

        boolean postOnly = Boolean.TRUE.equals(payload.postOnly());
        long displayQuantity = payload.displayQuantity() != null ? payload.displayQuantity() : quantity;
        if (displayQuantity <= 0 || displayQuantity > quantity) {
            throw new IllegalArgumentException("displayQuantity must be between 1 and the total quantity");
        }

        return new Order(
                assignedOrderId,
                userId,
                ticker,
                side,
                type,
                timeInForce,
                quantity,
                bookPrice,
                bookTriggerPrice,
                postOnly,
                displayQuantity);
    }

    private static OrderSide parseSide(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Side token missing");
        }
        char c = Character.toUpperCase(token.charAt(0));
        if (c == 'B') {
            return OrderSide.BUY;
        }
        if (c == 'S') {
            return OrderSide.SELL;
        }
        throw new IllegalArgumentException("Unknown side token: " + token);
    }

    private static ParsedOrderAttributes resolveOrderAttributes(String orderTypeToken, String timeInForceToken) {
        OrderType orderType = null;
        TimeInForce timeInForce = null;

        if (orderTypeToken != null && !orderTypeToken.isBlank()) {
            String normalized = normalizeToken(orderTypeToken);
            switch (normalized) {
                case "MARKET" -> orderType = OrderType.MARKET;
                case "LIMIT" -> orderType = OrderType.LIMIT;
                case "STOPMARKET", "STOP" -> orderType = OrderType.STOP_MARKET;
                case "STOPLIMIT" -> orderType = OrderType.STOP_LIMIT;
                case "GOODTILLCANCEL", "GTC" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.GTC;
                }
                case "GOODFORDAY", "DAY" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.DAY;
                }
                case "FILLANDKILL", "FAK", "IOC" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.IOC;
                }
                case "FILLORKILL", "FOK" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.FOK;
                }
                default -> throw new IllegalArgumentException("Unsupported order type token '" + orderTypeToken + "'");
            }
        }

        TimeInForce tifFromToken = parseTimeInForceToken(timeInForceToken);
        if (tifFromToken != null) {
            timeInForce = tifFromToken;
        }

        if (orderType == null) {
            orderType = OrderType.LIMIT;
        }

        if (timeInForce == null) {
            timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC;
        }

        return new ParsedOrderAttributes(orderType, timeInForce);
    }

    private static TimeInForce parseTimeInForceToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = normalizeToken(token);
        return switch (normalized) {
            case "GTC", "GOODTILLCANCEL" -> TimeInForce.GTC;
            case "DAY", "GFD", "GOODFORDAY" -> TimeInForce.DAY;
            case "IOC", "FAK", "FILLANDKILL" -> TimeInForce.IOC;
            case "FOK", "FILLORKILL" -> TimeInForce.FOK;
            default -> throw new IllegalArgumentException("Unsupported time-in-force token '" + token + "'");
        };
    }

    private static String normalizeToken(String token) {
        return token.replace("_", "").replace("-", "").replace(" ", "").toUpperCase();
    }

    private static boolean isSupportedTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        String normalized = normalizeTicker(ticker);
        for (Map<String, Object> instrument : INSTRUMENTS) {
            Object value = instrument.get("ticker");
            if (value instanceof String existing && normalized.equalsIgnoreCase(existing)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static List<SeedAccount> loadSeedAccounts() {
        try (InputStream stream = Main.class.getResourceAsStream("/accounts.json")) {
            if (stream == null) {
                return List.of();
            }
            JsonElement root = JSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                LOG.warn("accounts.json must be a JSON array of account definitions");
                return List.of();
            }
            List<SeedAccount> seeds = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String userId = getOptionalString(object, "userId");
                String apiKey = getOptionalString(object, "apiKey");
                if (userId == null || apiKey == null) {
                    LOG.warn("Skipping seed account with missing userId or apiKey");
                    continue;
                }
                double cash = object.has("cash") ? object.get("cash").getAsDouble() : 0.0d;
                boolean admin = object.has("admin") && object.get("admin").getAsBoolean();
                Map<String, Long> positions = extractPositions(object);
                seeds.add(new SeedAccount(userId, apiKey, cash, positions, admin));
            }
            return List.copyOf(seeds);
        } catch (Exception ex) {
            LOG.warn("Failed to load accounts.json", ex);
            return List.of();
        }
    }

    private static Map<String, Long> extractPositions(JsonObject object) {
        if (!object.has("positions") || !object.get("positions").isJsonObject()) {
            return Map.of();
        }
        Map<String, Long> positions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.get("positions").getAsJsonObject().entrySet()) {
            try {
                positions.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue().getAsLong());
            } catch (NumberFormatException | UnsupportedOperationException ex) {
                LOG.warn("Invalid position quantity for ticker {}: {}", entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(positions);
    }

    private static String getOptionalString(JsonObject object, String member) {
        if (object.has(member) && object.get(member).isJsonPrimitive()) {
            String value = object.get(member).getAsString();
            return value != null && !value.isBlank() ? value : null;
        }
        return null;
    }

    private record SeedAccount(String userId, String apiKey, double cash, Map<String, Long> positions, boolean admin) {
    }

    private record ParsedOrderAttributes(OrderType orderType, TimeInForce timeInForce) {
    }

    private static List<String> parseScriptPayload(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }

        JsonElement root;
        try {
            root = JSON.fromJson(rawBody, JsonElement.class);
        } catch (JsonSyntaxException ex) {
            throw new IllegalArgumentException("Body must be valid JSON");
        }

        if (root == null) {
            return List.of();
        }

        if (root.isJsonArray()) {
            return extractCommandsFromArray(root.getAsJsonArray());
        }

        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("commands")) {
                JsonElement commandsElement = object.get("commands");
                if (!commandsElement.isJsonArray()) {
                    throw new IllegalArgumentException("'commands' property must be an array");
                }
                return extractCommandsFromArray(commandsElement.getAsJsonArray());
            }
        }

        throw new IllegalArgumentException("Script payload must be an array of command strings");
    }

    private static List<String> extractCommandsFromArray(JsonArray array) {
        List<String> commands = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                commands.add(element.getAsString());
                continue;
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("command") && obj.get("command").isJsonPrimitive()) {
                    commands.add(obj.get("command").getAsString());
                    continue;
                }
                if (obj.has("value") && obj.get("value").isJsonPrimitive()) {
                    commands.add(obj.get("value").getAsString());
                    continue;
                }
            }
            throw new IllegalArgumentException("Unsupported script entry: " + element);
        }
        return commands;
    }

    private record OrderPayload(
        String orderId,
        String ticker,
        String orderType,
        String timeInForce,
        String side,
        Double price,
        Double triggerPrice,
        Long quantity,
        Boolean postOnly,
        Long displayQuantity) {
    }
}
