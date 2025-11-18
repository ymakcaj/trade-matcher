# Trade Matcher

A full-stack reference implementation of a limit-order matching engine. The backend (Javalin + Java 17) exposes authenticated REST and WebSocket APIs, while the React frontend consumes those feeds to visualise state, manage accounts, and drive scripted order flow.

## Project Layout

- `backend/` – matching engine, account model, REST + WebSocket services.
- `frontend/` – React UI wiring authentication, live market data, and admin tools.

Run both modules side-by-side for an interactive demo. The backend logs demo API tokens for the provisioned accounts (`admin`, `alpha`, `beta`) at startup.

## Order Types & Lifecycles

Supported order types are defined in `backend/src/main/java/tradeMatcher/OrderType.java` and `TimeInForce.java`. The engine also honours optional flags such as **post-only** (rejects any resting order that would cross the market) and iceberg-style **display quantity** (only a portion of the order is externally visible).

| Order Type | Behaviour | Typical Time-in-Force Defaults | Notes |
| --- | --- | --- | --- |
| `MARKET` | Aggressively executes against the book immediately. Any remaining quantity is rejected; the order never rests. | Defaults to `IOC`. | Converted into a temporary GTC order at the worst visible price to normalise fills when book depth is available. |
| `LIMIT` | Resting order at a specified price. Matches when contra liquidity satisfies price. | Defaults to `GTC` unless an explicit TIF is provided. | Supports all TIF variants (`DAY`, `IOC`, `FOK`, `GTC`). |
| `STOP_MARKET` | Converts to a market order when the trigger price is printed. | Inherits `IOC`. | Trigger price must be above (buys) or below (sells) the stop threshold after scaling. |
| `STOP_LIMIT` | Converts to a limit order at a specified price after the trigger prints. | Defaults to `GTC`. | Uses separate trigger and limit prices; both must respect tick size. |

### Time-in-Force semantics

| TIF | Meaning in the engine |
| --- | --- |
| `GTC` | The order persists until filled or explicitly canceled. |
| `DAY` | Automatically purged at 16:00 in the engine's local timezone by a background sweeper thread. |
| `IOC` | Executes whatever quantity is available immediately; any remainder is canceled instead of resting. |
| `FOK` | Must execute in full in the opening matching pass; otherwise the submission is rejected without resting. |

## How Matching Works

The heart of the system lives in [`MatchingEngine`](backend/src/main/java/tradeMatcher/MatchingEngine.java) and [`Orderbook`](backend/src/main/java/tradeMatcher/Orderbook.java).

1. **Pre-trade validation**
   - `AccountManager` verifies buying power for buys and inventory for sells, enforcing cash and position limits.
   - `MatchingEngine` enforces the post-only constraint before the order hits the book.
   - Stop/limit combinations are normalised using the instrument's `PriceScale` (tick size + fixed-point conversion).

2. **Book representation**
   - Bids and asks are maintained as two `TreeMap` structures keyed by price with FIFO (`ArrayDeque`) queues per price level, guaranteeing price-time priority.
   - `Orderbook` maintains aggregated level data for publishing depth snapshots and efficiently computing `FOK` feasibility.

3. **Insertion and matching**
   - When `AddOrder` is called, market and stop orders are converted into their executable variants (respecting thresholds) before entering the book.
   - IOC and FOK orders short-circuit if the pre-check determines they cannot satisfy their constraints.
   - Matching loops while the best bid crosses the best ask, pairing the head orders at each level. Each fill produces two `TradeInfo` entries (bid and ask sides) with quantities and scaled display prices.

4. **Post-trade effects**
   - Filled quantities adjust account balances/positions and are recorded as `FillRecord` instances, including a monotonic `fillId`.
   - Remaining resting orders update per-level aggregates so subsequent TIF checks and public deltas remain accurate.
   - `DAY` orders are cleaned up by a background pruning thread; `IOC` leftovers are auto-canceled after the initial match loop.

5. **Lifecycle control**
   - `ModifyOrder` is implemented as cancel + re-add while preserving the original `OrderType` and `TimeInForce`.
   - `/api/reset` nukes all open interest and cached fills while keeping accounts intact.
   - The admin-only script runner ingests additive, cancel, and modify commands to exercise the engine quickly.

## What the Engine Publishes

### Public Surface

Accessible without authentication:

- **WebSocket** `GET /ws/public`
  - Initial payload: `{ type: "SNAPSHOT", ticker, bids, asks }` covering full depth for the configured instrument.
  - Incremental deltas: `{ type: "LOB_UPDATE", changes: [[side, price, qty], ...] }` for price-level updates.
  - Trade bursts: `{ type: "TRADES", data: [...] }` whenever matches occur (contains anonymised bid/ask order ids and user ids if available).
- **REST**
  - `GET /api/instruments` – static metadata (tick size, minimum quantity).
  - `GET /api/market/status` – current trading session state.
   - `GET /api/market/{ticker}/book` – current full-depth snapshot for a supported instrument (bids + asks as published on the public WebSocket).

These feeds expose no account identifiers beyond anonymised trade references; REST endpoints never leak account balances.

### Private (Authenticated) Surface

Requires a bearer token in the `Authorization` header or `token` query parameter for the private WebSocket.

- **REST endpoints** (see `Main.java` for full definitions):
  - `GET /api/account` – cash + per-ticker positions for the authenticated user.
  - `GET /api/orders` – each open order with side, type, price, and remaining quantity.
  - `GET /api/fills` – historical fills attributed to the account.
   - `POST /api/order` – submit orders (enforces pre-trade checks; returns JSON with the server-assigned `orderId` while echoing an optional client-supplied reference as `clientOrderId`).
  - `DELETE /api/order/{id}` – cancel an existing order belonging to the caller.
  - `POST /api/script` & `POST /api/reset` – admin-only controls for scripted flows and full engine resets.

- **Private WebSocket** `GET /ws/private?token=<API_TOKEN>`
   - Emits lifecycle events scoped to the user: `ACK`, `REJECT`, `CANCELED`, and `FILL` messages, each carrying the authoritative `orderId`, relevant quantities/prices, timestamps, and (when provided on submission) the matching `clientOrderId` to help reconcile pending orders.
  - Multiple sessions per user are supported; every event fan-outs to all active connections owned by the token holder.

The backend never transmits another user's balances, positions, or order details over private channels—only events that belong to the authenticated account.

## Integer Price Math

### Why convert to integers?

All matching logic works with integer "price keys" instead of double precision decimals. For example, `PriceScale` normalises a displayed price such as `123.456` into the integer `123456` when the tick size is `0.001`. That choice matters because:

- **Deterministic Arithmetic** – Floating point addition/subtraction suffers from rounding drift. Integer math keeps per-level quantity/price aggregation exact, which is critical when enforcing fill-or-kill checks or comparing book deltas.
- **Performance and cache locality** – Primitive `int` arithmetic is cheaper for the JVM JIT to optimise than repeated boxing/unboxing of `BigDecimal` or `Double`. Combined with `TreeMap<Integer,Deque<Order>>`, prices become array-index friendly keys that sit tightly packed in CPU caches.
- **Consistent tick enforcement** – Scaling prices up front automatically rejects orders that violate the configured tick size. A `PriceScale` converts from display units to book units and back, so validation and broadcast never disagree.

You can inspect the conversion utilities in [`PriceScale`](backend/src/main/java/tradeMatcher/PriceScale.java). Every order goes through `PriceScaleProvider.getRegistry().getScale(ticker)` before entering the book, and public feeds format the stored integer back into a string with three decimal places. This dual representation ensures the engine enjoys integer-speed comparisons while the UI and API surface remain human-friendly.

## Running the Demo Locally

1. **Backend**
   ```bash
   cd backend
   mvn clean package
   java -jar target/trade-matcher.jar
   ```
   The console prints API tokens; copy one to authenticate REST and private WebSocket calls.

2. **Frontend**
   ```bash
   cd ../frontend
   npm install
   npm start
   ```
   The React app reads `REACT_APP_API_URL` (default `http://localhost:7070`) and stores tokens in `localStorage`.

## Further Reading

- `backend/src/main/java/tradeMatcher/Orderbook.java` – detailed matching and level aggregation logic.
- `backend/src/main/java/tradeMatcher/MatchingEngine.java` – integration with accounts, feeds, and lifecycle events.
- [`PriceScale`](backend/src/main/java/tradeMatcher/PriceScale.java) & [`PriceScaleProvider`](backend/src/main/java/tradeMatcher/PriceScaleProvider.java) – tick-size aware integer conversion helpers used throughout.
- `frontend/src/AppRoot.js` – client orchestration of REST calls and sockets.
- `frontend/src/components/` – UI components for orders, fills, scripts, and charts.

Contributions and enhancements are welcome—file issues or PRs on the `feature/switch_to_websocket` branch to keep the conversation grounded in live data handling.
