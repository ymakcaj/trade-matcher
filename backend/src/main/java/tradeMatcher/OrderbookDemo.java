package tradeMatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Small entry point that replays an order script and prints the resulting order book.
 */
public final class OrderbookDemo {
    private static final String DEFAULT_SCRIPT = "/TestFiles/Cancel_Success.txt";
    private static final String VERBOSE_FLAG = "--verbose";
    private static final String VERBOSE_SHORT_FLAG = "-v";
    private static boolean verboseMode;
    private static final PriceScaleRegistry PRICE_SCALES = PriceScaleProvider.getRegistry();

    private OrderbookDemo() {
    }

    public static void main(String[] args) {
        verboseMode = false;
        String scriptArgument = null;

        for (String arg : args) {
            if (isVerboseFlag(arg)) {
                verboseMode = true;
            } else if (scriptArgument == null) {
                scriptArgument = arg;
            } else {
                System.err.println("Unexpected argument: " + arg);
                return;
            }
        }

        List<String> script;
        try {
            script = loadScript(scriptArgument);
        } catch (IOException ex) {
            System.err.println("Failed to load script: " + ex.getMessage());
            return;
        }

        try (Orderbook orderbook = new Orderbook()) {
            ExpectedSnapshot expected = null;

            for (int i = 0; i < script.size(); i++) {
                String rawLine = script.get(i);
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                try {
                    ExpectedSnapshot snapshot = processLine(orderbook, line);
                    if (snapshot != null) {
                        expected = snapshot;
                    }
                    pause();
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("Failed to process line " + (i + 1) + ": " + rawLine, ex);
                }
            }

            printSnapshot(orderbook);
            if (expected != null) {
                validateExpected(orderbook, expected);
            }
        }
    }

    private static List<String> loadScript(String scriptArgument) throws IOException {
        if (scriptArgument != null) {
            Path path = Path.of(scriptArgument);
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        }

        try (InputStream stream = OrderbookDemo.class.getResourceAsStream(DEFAULT_SCRIPT)) {
            if (stream == null) {
                throw new IOException("Missing default script resource: " + DEFAULT_SCRIPT);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                return lines;
            }
        }
    }

    private static ExpectedSnapshot processLine(Orderbook orderbook, String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }

        char code = Character.toUpperCase(tokens[0].charAt(0));
        return switch (code) {
            case 'A' -> handleAdd(orderbook, tokens);
            case 'C' -> handleCancel(orderbook, tokens);
            case 'M' -> handleModify(orderbook, tokens);
            case 'R' -> parseExpected(tokens);
            default -> throw new IllegalArgumentException("Unsupported command: " + tokens[0]);
        };
    }

    private static ExpectedSnapshot handleAdd(Orderbook orderbook, String[] tokens) {
        if (tokens.length < 6) {
            throw new IllegalArgumentException("Add command requires 6 tokens");
        }

        OrderSide side = parseSide(tokens[1]);
        ParsedOrderSpec spec = parseOrderSpec(tokens[2]);
        double price = parseDouble(tokens[3], "price");
        int quantity = parseInt(tokens[4], "quantity");
        long orderId = parseOrderId(tokens[5]);

        PriceScale scale = PRICE_SCALES.getScale("DEMO");
        int bookPrice = spec.orderType() == OrderType.MARKET ? 0 : scale.toBookPrice(price);
        int bookTrigger = (spec.orderType() == OrderType.STOP_MARKET || spec.orderType() == OrderType.STOP_LIMIT)
                ? scale.toBookPrice(price)
                : bookPrice;

        if (verboseMode) {
            System.out.printf("ORDER SUBMIT orderId=%d side=%s type=%s price=%d qty=%d%n",
                    orderId,
                    side,
                    spec.orderType(),
                    price,
                    quantity);
        }

    Order order = new Order(
        String.valueOf(orderId),
        "demo-cli",
        "DEMO",
        side,
        spec.orderType(),
        spec.timeInForce(),
        quantity,
        bookPrice,
        bookTrigger,
        false,
        quantity);

        List<Trade> trades = orderbook.AddOrder(order);
        if (logTrades(orderbook, trades)) {
            return null;
        }

        List<OrderDetails> details = orderbook.GetOrderDetails();
        boolean added = details.stream().anyMatch(o -> o.getOrderId() == orderId);
        if (verboseMode || !added) {
            String status = added ? "ACCEPTED" : "REJECTED";
            System.out.printf("ORDER %s orderId=%d side=%s type=%s price=%d qty=%d%n",
                    status,
                    orderId,
                    side,
                    spec.orderType(),
                    price,
                    quantity);
        }

        printSnapshot(orderbook, details);
        return null;
    }

    private static ExpectedSnapshot handleCancel(Orderbook orderbook, String[] tokens) {
        if (tokens.length < 2) {
            throw new IllegalArgumentException("Cancel command requires an order id");
        }

        long orderId = parseOrderId(tokens[1]);
        orderbook.CancelOrder(orderId);
        return null;
    }

    private static ExpectedSnapshot handleModify(Orderbook orderbook, String[] tokens) {
        if (tokens.length < 5) {
            throw new IllegalArgumentException("Modify command requires 5 tokens");
        }

        long orderId = parseOrderId(tokens[1]);
        OrderSide side = parseSide(tokens[2]);
        double price = parseDouble(tokens[3], "price");
        int quantity = parseInt(tokens[4], "quantity");

        PriceScale scale = PRICE_SCALES.getScale("DEMO");
        int bookPrice = scale.toBookPrice(price);

        List<Trade> trades = orderbook.ModifyOrder(new OrderModify(orderId, "demo-cli", "DEMO", side, bookPrice, quantity));
        if (logTrades(orderbook, trades)) {
            return null;
        }
        printSnapshot(orderbook);
        return null;
    }

    private static ExpectedSnapshot parseExpected(String[] tokens) {
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Result command requires 3 numeric values");
        }

    int total = parseInt(tokens[1], "total");
    int bids = parseInt(tokens[2], "bids");
    int asks = parseInt(tokens[3], "asks");
        return new ExpectedSnapshot(total, bids, asks);
    }

    private static void pause() {
        try {
            Thread.sleep(3000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean logTrades(Orderbook orderbook, List<Trade> trades) {
        boolean printed = false;
        for (Trade trade : trades) {
            TradeInfo bid = Objects.requireNonNull(trade.getBidTrade(), "Missing bid trade info");
            TradeInfo ask = Objects.requireNonNull(trade.getAskTrade(), "Missing ask trade info");
        int quantity = bid.getQuantity();
        double price = bid.getPrice();
        System.out.printf("TRADE qty=%d price=%.4f buyOrder=%d sellOrder=%d%n",
                    quantity,
                    price,
                    bid.getOrderId(),
                    ask.getOrderId());
            printSnapshot(orderbook);
            printed = true;
        }
        return printed;
    }

    private static void printSnapshot(Orderbook orderbook) {
        printSnapshot(orderbook, orderbook.GetOrderDetails());
    }

    private static void printSnapshot(Orderbook orderbook, List<OrderDetails> orders) {
        System.out.println("=== Orderbook Snapshot ===");
        OrderbookLevelInfos infos = orderbook.GetOrderInfos();
        printSide("Bids", infos.GetBids());
        printSide("Asks", infos.GetAsks());
        printOrders(orders);
        System.out.printf("Total open orders: %d%n", orderbook.Size());
    }

    private static void printSide(String label, List<LevelInfo> levels) {
        System.out.println(label + ":");
        if (levels.isEmpty()) {
            System.out.println("  (none)");
            return;
        }

        System.out.println("  Price      Quantity");
        for (LevelInfo level : levels) {
            System.out.printf("  %7.4f    %8d%n", level.getPrice(), level.getQuantity());
        }
    }

    private static void printOrders(List<OrderDetails> orders) {
        System.out.println("Open Orders:");
        if (orders.isEmpty()) {
            System.out.println("  (none)");
            return;
        }

        System.out.println("  Id    Side  Type           Price  Remaining");
        for (OrderDetails order : orders) {
        System.out.printf("  %-5d %-5s %-14s %7.4f  %9d%n",
                    order.getOrderId(),
                    order.getSide(),
                    order.getOrderType(),
                    order.getPrice(),
                    order.getRemainingQuantity());
        }
    }

    private static void validateExpected(Orderbook orderbook, ExpectedSnapshot expected) {
        int total = orderbook.Size();
        OrderbookLevelInfos infos = orderbook.GetOrderInfos();
        int bidLevels = infos.GetBids().size();
        int askLevels = infos.GetAsks().size();

        if (total == expected.total() && bidLevels == expected.bids() && askLevels == expected.asks()) {
            System.out.println("Script summary matches current orderbook state.");
        } else {
            System.out.printf("Script summary mismatch: expected total=%d bids=%d asks=%d but was total=%d bids=%d asks=%d%n",
                    expected.total(),
                    expected.bids(),
                    expected.asks(),
                    total,
                    bidLevels,
                    askLevels);
        }
    }

    private static OrderSide parseSide(String token) {
        String normalized = token.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "B" -> OrderSide.BUY;
            case "S" -> OrderSide.SELL;
            default -> throw new IllegalArgumentException("Unknown side: " + token);
        };
    }

    private static ParsedOrderSpec parseOrderSpec(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Order type token is required");
        }
        String normalized = token.replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MARKET" -> new ParsedOrderSpec(OrderType.MARKET, TimeInForce.IOC);
            case "LIMIT" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.GTC);
            case "STOPMARKET", "STOP" -> new ParsedOrderSpec(OrderType.STOP_MARKET, TimeInForce.IOC);
            case "STOPLIMIT" -> new ParsedOrderSpec(OrderType.STOP_LIMIT, TimeInForce.GTC);
            case "GOODTILLCANCEL", "GTC" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.GTC);
            case "GOODFORDAY", "DAY" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.DAY);
            case "FILLANDKILL", "IOC", "FAK" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.IOC);
            case "FILLORKILL", "FOK" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.FOK);
            default -> throw new IllegalArgumentException("Unsupported order type token: " + token);
        };
    }

    private static int parseInt(String token, String label) {
        long value = parseUnsignedLong(token, label);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Value too large for " + label + ": " + token);
        }
        return (int) value;
    }

    private static double parseDouble(String token, String label) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid decimal value for " + label + ": " + token, ex);
        }
    }

    private static long parseOrderId(String token) {
        long value = parseUnsignedLong(token, "order id");
        if (value < 0) {
            throw new IllegalArgumentException("Order id must be non-negative: " + token);
        }
        return value;
    }

    private static long parseUnsignedLong(String token, String label) {
        Objects.requireNonNull(token, "token");
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Empty value for " + label);
        }
        long value = Long.parseLong(token);
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative: " + token);
        }
        return value;
    }

    private static boolean isVerboseFlag(String arg) {
        String normalized = arg.toLowerCase(Locale.ROOT);
        return VERBOSE_FLAG.equals(normalized) || VERBOSE_SHORT_FLAG.equals(normalized);
    }

    private record ExpectedSnapshot(int total, int bids, int asks) {
    }

    private record ParsedOrderSpec(OrderType orderType, TimeInForce timeInForce) {
    }
}
