import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import './App.css';
import AuthPanel from './components/AuthPanel';
import AccountSummary from './components/AccountSummary';
import OrderForm from './components/OrderForm';
import OrderBook from './components/OrderBook';
import TradeHistory from './components/TradeHistory';
import ScriptBuilder from './components/ScriptBuilder';
import ScriptUploader from './components/ScriptUploader';
import OrderLog from './components/OrderLog';
import BestBidAskChart from './components/BestBidAskChart';
import OrdersTable from './components/OrdersTable';
import FillsTable from './components/FillsTable';

const DEFAULT_TICKER = 'TEST';
const TRADE_LIMIT = 500;
const EVENT_LIMIT = 500;
const PRICE_HISTORY_LIMIT = 2000;

const appendWithLimit = (list, entry, limit) => {
  const next = [...list, entry];
  return next.length > limit ? next.slice(next.length - limit) : next;
};

const resolveApiBase = () => {
  const fromEnv = process.env.REACT_APP_API_URL;
  if (fromEnv && typeof fromEnv === 'string') {
    return fromEnv.replace(/\/$/, '');
  }
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin.replace(/\/$/, '');
  }
  return 'http://localhost:7070';
};

const toWebSocketBase = (httpBase) => {
  if (!httpBase) {
    return 'ws://localhost:7070';
  }
  if (httpBase.startsWith('https://')) {
    return `wss://${httpBase.substring('https://'.length)}`;
  }
  if (httpBase.startsWith('http://')) {
    return `ws://${httpBase.substring('http://'.length)}`;
  }
  if (httpBase.startsWith('ws://') || httpBase.startsWith('wss://')) {
    return httpBase;
  }
  return `ws://${httpBase.replace(/^\/+/, '')}`;
};

const safeParse = (raw) => {
  try {
    return JSON.parse(raw);
  } catch (error) {
    return null;
  }
};

const buildLevelEntry = (priceKey, quantity) => ({
  key: priceKey,
  price: Number.parseFloat(priceKey),
  quantity: Number.parseInt(quantity, 10)
});

const toSortedLevels = (entries, side) => {
  const items = Array.from(entries.values()).filter((entry) => Number.isFinite(entry.quantity) && entry.quantity > 0);
  const comparator = side === 'bid'
    ? (a, b) => b.price - a.price
    : (a, b) => a.price - b.price;
  return items.sort(comparator);
};

function AppRoot() {
  const initialToken = useMemo(() => {
    if (typeof window === 'undefined') {
      return '';
    }
    return window.localStorage.getItem('tm_api_token') ?? '';
  }, []);

  const [authToken, setAuthToken] = useState(initialToken);
  const [userAccount, setUserAccount] = useState(null);
  const [positions, setPositions] = useState([]);
  const [openOrders, setOpenOrders] = useState([]);
  const [fills, setFills] = useState([]);
  const [orderBook, setOrderBook] = useState({ bids: [], asks: [] });
  const [priceHistory, setPriceHistory] = useState([]);
  const [rawTrades, setRawTrades] = useState([]);
  const [orderEvents, setOrderEvents] = useState([]);
  const [publicStatus, setPublicStatus] = useState('disconnected');
  const [privateStatus, setPrivateStatus] = useState(initialToken ? 'connecting' : 'idle');
  const [isResetting, setIsResetting] = useState(false);
  const [refreshingAccount, setRefreshingAccount] = useState(false);
  const [lastError, setLastError] = useState(null);

  const bookRef = useRef({ bids: new Map(), asks: new Map() });
  const pendingOrdersRef = useRef(new Map());
  const clientOrderSeqRef = useRef(1);

  const generateClientOrderId = useCallback(() => {
    const seq = clientOrderSeqRef.current++;
    const base = Date.now().toString(36);
    return `TMP-${base}-${seq.toString(36)}`;
  }, []);

  const rekeyPendingOrder = useCallback((fromKey, toKey) => {
    if (!fromKey || !toKey) {
      return;
    }
    const source = pendingOrdersRef.current.get(fromKey);
    if (!source) {
      return;
    }
    const payload = {
      ...source,
      orderId: toKey,
      serverOrderId: toKey,
      clientOrderId: source.clientOrderId ?? fromKey
    };
    pendingOrdersRef.current.set(toKey, payload);
    if (fromKey !== toKey) {
      pendingOrdersRef.current.delete(fromKey);
    }
  }, []);

  const removePendingOrder = useCallback((...keys) => {
    keys.forEach((key) => {
      if (!key && key !== 0) {
        return;
      }
      const normalized = String(key);
      pendingOrdersRef.current.delete(normalized);
    });
  }, []);

  const apiBase = useMemo(() => resolveApiBase(), []);
  const wsBase = useMemo(() => toWebSocketBase(apiBase), [apiBase]);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (authToken) {
      window.localStorage.setItem('tm_api_token', authToken);
    } else {
      window.localStorage.removeItem('tm_api_token');
    }
  }, [authToken]);

  const pushEvent = useCallback((event) => {
    setOrderEvents((prev) => appendWithLimit(prev, event, EVENT_LIMIT));
  }, []);

  const clearUserState = useCallback(() => {
    setUserAccount(null);
    setPositions([]);
    setOpenOrders([]);
    setFills([]);
    pendingOrdersRef.current.clear();
  }, []);

  const handleDisconnect = useCallback(() => {
    clearUserState();
    setAuthToken('');
    setPrivateStatus('idle');
    pushEvent({
      timestamp: Date.now(),
      phase: 'SESSION',
      orderId: null,
      side: null,
      orderType: null,
      price: null,
      quantity: null,
      message: 'Disconnected',
      severity: 'info'
    });
  }, [clearUserState, pushEvent]);

  const authorizedFetch = useCallback(async (path, init = {}) => {
    if (!authToken) {
      const error = new Error('Authentication required');
      error.code = 'NO_AUTH';
      throw error;
    }
    const headers = new Headers(init.headers || {});
    headers.set('Authorization', `Bearer ${authToken}`);
    if (init.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    const response = await fetch(`${apiBase}${path}`, { ...init, headers });
    if (response.status === 401) {
      const error = new Error('Unauthorized');
      error.code = 'UNAUTHORIZED';
      throw error;
    }
    return response;
  }, [apiBase, authToken]);

  const refreshAccount = useCallback(async () => {
    if (!authToken) {
      return;
    }
    setRefreshingAccount(true);
    try {
      const response = await authorizedFetch('/api/account');
      const data = await response.json();
      setUserAccount({
        userId: data.userId,
        cash: data.cash
      });
      const sortedPositions = Array.isArray(data.positions)
        ? data.positions.slice().sort((a, b) => a.ticker.localeCompare(b.ticker))
        : [];
      setPositions(sortedPositions);
      setLastError(null);
    } catch (error) {
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        setLastError(error.message);
      }
    } finally {
      setRefreshingAccount(false);
    }
  }, [authToken, authorizedFetch, handleDisconnect]);

  const refreshOrders = useCallback(async () => {
    if (!authToken) {
      setOpenOrders([]);
      return;
    }
    try {
      const response = await authorizedFetch('/api/orders');
      const data = await response.json();
      if (Array.isArray(data)) {
        setOpenOrders(data);
      } else {
        setOpenOrders([]);
      }
      setLastError(null);
    } catch (error) {
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        setLastError(error.message);
      }
    }
  }, [authToken, authorizedFetch, handleDisconnect]);

  const refreshFills = useCallback(async () => {
    if (!authToken) {
      setFills([]);
      return;
    }
    try {
      const response = await authorizedFetch('/api/fills');
      const data = await response.json();
      if (Array.isArray(data)) {
        const mapped = data.map((fill) => ({
          ...fill,
          userId: fill.userId ?? userAccount?.userId ?? null
        }))
          .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        setFills(mapped);
      } else {
        setFills([]);
      }
      setLastError(null);
    } catch (error) {
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        setLastError(error.message);
      }
    }
  }, [authToken, authorizedFetch, handleDisconnect, userAccount?.userId]);

  const getOrderMetadata = useCallback((orderId) => {
    if (!orderId) {
      return null;
    }
    const key = String(orderId);
    const fromPending = pendingOrdersRef.current.get(key);
    if (fromPending) {
      return fromPending;
    }
    for (const value of pendingOrdersRef.current.values()) {
      if (!value) {
        continue;
      }
      if (value.orderId && String(value.orderId) === key) {
        return value;
      }
      if (value.clientOrderId && String(value.clientOrderId) === key) {
        return value;
      }
      if (value.serverOrderId && String(value.serverOrderId) === key) {
        return value;
      }
    }
    return openOrders.find((order) => String(order.orderId) === key) ?? null;
  }, [openOrders]);

  const buildEventPayload = useCallback((phase, details) => {
    const metadata = details.orderId ? getOrderMetadata(details.orderId) : null;
    const priceValue = details.price ?? metadata?.price ?? null;
    return {
      timestamp: Date.now(),
      phase,
      orderId: details.orderId ?? metadata?.orderId ?? null,
      side: details.side ?? metadata?.side ?? null,
      orderType: details.orderType ?? metadata?.orderType ?? null,
      price: typeof priceValue === 'number' ? priceValue.toFixed(3) : priceValue,
      quantity: details.quantity ?? metadata?.quantity ?? null,
      message: details.message ?? null,
      severity: details.severity ?? 'info'
    };
  }, [getOrderMetadata]);

  const clearMarketState = useCallback(() => {
    bookRef.current = { bids: new Map(), asks: new Map() };
    setOrderBook({ bids: [], asks: [] });
    setPriceHistory([]);
    setRawTrades([]);
  }, []);

  const applyBookState = useCallback((book) => {
    const bids = toSortedLevels(book.bids, 'bid').slice(0, 50);
    const asks = toSortedLevels(book.asks, 'ask').slice(0, 50);
    setOrderBook({ bids, asks });
    const bestBid = bids.length ? bids[0].price : null;
    const bestAsk = asks.length ? asks[0].price : null;
    setPriceHistory((prev) => appendWithLimit(prev, {
      time: Date.now(),
      bestBid,
      bestAsk
    }, PRICE_HISTORY_LIMIT));
  }, []);

  const applySnapshot = useCallback((snapshot) => {
    if (!snapshot || !Array.isArray(snapshot.bids) || !Array.isArray(snapshot.asks)) {
      return;
    }
    const bids = new Map();
    const asks = new Map();
    snapshot.bids.forEach((level) => {
      const priceKey = typeof level.price === 'number' ? level.price.toFixed(3) : String(level.price);
      bids.set(priceKey, buildLevelEntry(priceKey, level.quantity));
    });
    snapshot.asks.forEach((level) => {
      const priceKey = typeof level.price === 'number' ? level.price.toFixed(3) : String(level.price);
      asks.set(priceKey, buildLevelEntry(priceKey, level.quantity));
    });
    bookRef.current = { bids, asks };
    applyBookState(bookRef.current);
  }, [applyBookState]);

  const applyDelta = useCallback((delta) => {
    if (!delta || !Array.isArray(delta.changes)) {
      return;
    }
    const nextBids = new Map(bookRef.current.bids);
    const nextAsks = new Map(bookRef.current.asks);
    delta.changes.forEach(([side, priceString, qtyString]) => {
      if (!priceString) {
        return;
      }
      const priceKey = priceString;
      const quantity = Number.parseInt(qtyString, 10);
      const target = side === 'BUY' ? nextBids : nextAsks;
      if (!Number.isFinite(quantity) || quantity <= 0) {
        target.delete(priceKey);
      } else {
        target.set(priceKey, buildLevelEntry(priceKey, quantity));
      }
    });
    bookRef.current = { bids: nextBids, asks: nextAsks };
    applyBookState(bookRef.current);
  }, [applyBookState]);

  const appendTradesFromFeed = useCallback((trades) => {
    if (!Array.isArray(trades) || trades.length === 0) {
      return;
    }
    const timestamp = Date.now();
    const mapped = trades.map((trade, index) => {
      const bid = trade?.bidTrade ?? {};
      const ask = trade?.askTrade ?? {};
      const price = typeof bid.price === 'number' ? bid.price : (typeof ask.price === 'number' ? ask.price : null);
      const quantity = bid.quantity ?? ask.quantity ?? null;
      return {
        id: `${timestamp}-${index}-${bid.orderId ?? ask.orderId ?? Math.random()}`,
        timestamp,
        price,
        quantity,
        bidOrderId: bid.orderId ?? null,
        askOrderId: ask.orderId ?? null,
        bidUserId: bid.userId ?? null,
        askUserId: ask.userId ?? null
      };
    });
    setRawTrades((prev) => {
      const combined = [...prev, ...mapped];
      return combined.length > TRADE_LIMIT ? combined.slice(combined.length - TRADE_LIMIT) : combined;
    });
  }, []);

  const handlePublicPayload = useCallback((payload) => {
    if (!payload) {
      return;
    }
    if (Array.isArray(payload)) {
      appendTradesFromFeed(payload);
      return;
    }
    if (payload.type === 'SNAPSHOT') {
      applySnapshot(payload);
      return;
    }
    if (payload.type === 'LOB_UPDATE') {
      applyDelta(payload);
      return;
    }
    if (payload.type === 'TRADES') {
      appendTradesFromFeed(payload.data);
      return;
    }
    if (Array.isArray(payload.trades)) {
      appendTradesFromFeed(payload.trades);
    }
    if (Array.isArray(payload.bids) && Array.isArray(payload.asks)) {
      applySnapshot(payload);
    }
  }, [appendTradesFromFeed, applyDelta, applySnapshot]);

  useEffect(() => {
    const url = `${wsBase.replace(/\/$/, '')}/ws/public`;
    const ws = new WebSocket(url);
    setPublicStatus('connecting');

    ws.onopen = () => setPublicStatus('connected');
    ws.onclose = () => setPublicStatus('disconnected');
    ws.onerror = () => setPublicStatus('error');
    ws.onmessage = (event) => {
      const payload = safeParse(event.data);
      handlePublicPayload(payload);
    };

    return () => {
      ws.close(1000, 'client close');
      setPublicStatus('disconnected');
    };
  }, [handlePublicPayload, wsBase]);

  const handlePrivateMessage = useCallback((payload) => {
    if (!payload || typeof payload !== 'object') {
      return;
    }
    const type = payload.type;
    const orderId = payload.orderId != null ? String(payload.orderId) : null;
    const clientOrderId = payload.clientOrderId != null ? String(payload.clientOrderId) : null;
    if (type === 'ACK') {
      if (clientOrderId && orderId) {
        rekeyPendingOrder(clientOrderId, orderId);
      }
      const identifier = orderId ?? clientOrderId;
      pushEvent(buildEventPayload('ACK', {
        orderId: identifier,
        message: 'Order acknowledged',
        severity: 'success'
      }));
      removePendingOrder(identifier, clientOrderId, orderId);
      refreshOrders();
      return;
    }
    if (type === 'REJECT') {
      const identifier = orderId ?? clientOrderId;
      pushEvent(buildEventPayload('REJECT', {
        orderId: identifier,
        message: payload.reason ?? 'Order rejected',
        severity: 'error'
      }));
      removePendingOrder(identifier, clientOrderId, orderId);
      return;
    }
    if (type === 'CANCELED') {
      const identifier = orderId ?? clientOrderId;
      pushEvent(buildEventPayload('CANCELED', {
        orderId: identifier,
        message: 'Order canceled',
        severity: 'warning'
      }));
      removePendingOrder(identifier, clientOrderId, orderId);
      refreshOrders();
      return;
    }
    if (type === 'FILL') {
      pushEvent(buildEventPayload('FILL', {
        orderId,
        message: `Fill ${payload.quantity} @ ${payload.price}`,
        quantity: payload.quantity,
        price: payload.price,
        severity: 'success'
      }));
      const fill = {
        fillId: payload.fillId,
        orderId: payload.orderId,
        userId: payload.userId,
        ticker: payload.ticker,
        side: payload.side,
        price: payload.price,
        quantity: payload.quantity,
        timestamp: payload.timestamp
      };
      setFills((prev) => {
        const withoutDuplicate = prev.filter((existing) => existing.fillId !== fill.fillId);
        const next = [fill, ...withoutDuplicate];
        return next.slice(0, 200);
      });
      refreshAccount();
      refreshOrders();
      return;
    }
  }, [buildEventPayload, pushEvent, refreshAccount, refreshOrders, rekeyPendingOrder, removePendingOrder]);

  useEffect(() => {
    if (!authToken) {
      setPrivateStatus('idle');
      return;
    }
    const tokenParam = encodeURIComponent(authToken);
    const url = `${wsBase.replace(/\/$/, '')}/ws/private?token=${tokenParam}`;
    const ws = new WebSocket(url);
    setPrivateStatus('connecting');

    ws.onopen = () => setPrivateStatus('connected');
    ws.onclose = () => setPrivateStatus('disconnected');
    ws.onerror = () => setPrivateStatus('error');
    ws.onmessage = (event) => {
      const payload = safeParse(event.data);
      handlePrivateMessage(payload);
    };

    return () => {
      ws.close(1000, 'client close');
      setPrivateStatus(authToken ? 'disconnected' : 'idle');
    };
  }, [authToken, handlePrivateMessage, wsBase]);

  useEffect(() => {
    if (!authToken) {
      return;
    }
    refreshAccount();
    refreshOrders();
    refreshFills();
  }, [authToken, refreshAccount, refreshOrders, refreshFills]);

  const handleConnect = useCallback((tokenValue) => {
    setLastError(null);
    setAuthToken(tokenValue);
    setPrivateStatus('connecting');
  }, []);

  const decoratedTrades = useMemo(() => {
    const viewerId = userAccount?.userId ?? null;
    return rawTrades.map((trade) => {
      const viewerRole = viewerId
        ? (trade.bidUserId === viewerId ? 'bid' : (trade.askUserId === viewerId ? 'ask' : null))
        : null;
      return { ...trade, viewerRole };
    });
  }, [rawTrades, userAccount?.userId]);

  const handleOrderSubmit = useCallback((order) => {
    const clientOrderId = generateClientOrderId();
    if (!authToken) {
      pushEvent({
        timestamp: Date.now(),
        phase: 'SUBMIT',
        orderId: clientOrderId,
        side: order.side,
        orderType: order.orderType,
        price: order.price,
        quantity: order.quantity,
        message: 'Authenticate before submitting orders',
        severity: 'error'
      });
      return;
    }

    const pendingPayload = {
      ...order,
      orderId: clientOrderId,
      clientOrderId,
      serverOrderId: null
    };
    pendingOrdersRef.current.set(clientOrderId, pendingPayload);

    pushEvent({
      timestamp: Date.now(),
      phase: 'SUBMIT',
      orderId: clientOrderId,
      side: order.side,
      orderType: order.orderType,
      price: typeof order.price === 'number' ? order.price.toFixed(3) : order.price,
      quantity: order.quantity,
      message: 'Submitting order',
      severity: 'info'
    });

  const submission = { ...order, orderId: clientOrderId };

    authorizedFetch('/api/order', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(submission)
    }).then(async (response) => {
      let body = {};
      try {
        body = await response.json();
      } catch (error) {
        body = {};
      }

      if (!response.ok) {
        const assignedOrderId = body?.orderId ? String(body.orderId) : null;
        const reason = body?.message ?? `HTTP ${response.status}`;
        removePendingOrder(clientOrderId, assignedOrderId);
        pushEvent(buildEventPayload('SUBMIT_FAILED', {
          orderId: assignedOrderId ?? clientOrderId,
          message: reason,
          severity: 'error'
        }));
        return;
      }

      const assignedOrderId = body?.orderId ? String(body.orderId) : null;
      if (assignedOrderId) {
        rekeyPendingOrder(clientOrderId, assignedOrderId);
      }
    }).catch((error) => {
      removePendingOrder(clientOrderId);
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        pushEvent(buildEventPayload('SUBMIT_FAILED', {
          orderId: clientOrderId,
          message: error.message,
          severity: 'error'
        }));
      }
    });
  }, [authToken, authorizedFetch, buildEventPayload, generateClientOrderId, handleDisconnect, pushEvent, rekeyPendingOrder, removePendingOrder]);

  const handleScriptSubmit = useCallback((scriptLines) => {
    if (!authToken) {
      pushEvent({
        timestamp: Date.now(),
        phase: 'SCRIPT',
        message: 'Authenticate before running scripts',
        severity: 'error'
      });
      return;
    }
    authorizedFetch('/api/script', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(scriptLines)
    }).then((response) => {
      if (!response.ok) {
        return response.json().catch(() => ({})).then((body) => {
          throw new Error(body?.message ?? `HTTP ${response.status}`);
        });
      }
      pushEvent({
        timestamp: Date.now(),
        phase: 'SCRIPT',
        message: `Submitted script with ${scriptLines.length} commands`,
        severity: 'info'
      });
      refreshOrders();
    }).catch((error) => {
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        pushEvent({
          timestamp: Date.now(),
          phase: 'SCRIPT',
          message: error.message,
          severity: 'error'
        });
      }
    });
  }, [authToken, authorizedFetch, handleDisconnect, pushEvent, refreshOrders]);

  const resetApplication = useCallback(async () => {
    if (!authToken) {
      pushEvent({
        timestamp: Date.now(),
        phase: 'RESET',
        message: 'Authenticate as admin to reset',
        severity: 'error'
      });
      return;
    }
    setIsResetting(true);
    try {
      const response = await authorizedFetch('/api/reset', { method: 'POST' });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body?.message ?? `HTTP ${response.status}`);
      }
      clearMarketState();
      pushEvent({
        timestamp: Date.now(),
        phase: 'RESET',
        message: 'Engine reset complete',
        severity: 'success'
      });
      await Promise.allSettled([refreshAccount(), refreshOrders(), refreshFills()]);
    } catch (error) {
      if (error.code === 'UNAUTHORIZED') {
        setLastError('Session expired. Please reconnect.');
        handleDisconnect();
      } else {
        setLastError(error.message);
        pushEvent({
          timestamp: Date.now(),
          phase: 'RESET',
          message: error.message,
          severity: 'error'
        });
      }
    } finally {
      setIsResetting(false);
    }
  }, [authToken, authorizedFetch, clearMarketState, handleDisconnect, pushEvent, refreshAccount, refreshFills, refreshOrders]);

  return (
    <div className="App">
      <header className="App-header">
        <div className="header-copy">
          <h1>Trade Tester</h1>
          <h2>Replay strategies and watch authenticated fills in real time</h2>
        </div>
        <button
          type="button"
          className="reset-button"
          onClick={resetApplication}
          disabled={!authToken || isResetting}
        >
          {isResetting ? 'Resetting…' : 'Reset'}
        </button>
      </header>

      {lastError && (
        <div className="app-error">{lastError}</div>
      )}

      <div className="auth-container">
        <AuthPanel
          token={authToken}
          userId={userAccount?.userId ?? null}
          onConnect={handleConnect}
          onDisconnect={handleDisconnect}
          publicStatus={publicStatus}
          privateStatus={privateStatus}
        />
      </div>

      <main className="app-main">
        <section className="column column-market">
          <OrderBook orderBook={orderBook} />
          <BestBidAskChart data={priceHistory} trades={decoratedTrades} />
          <TradeHistory trades={decoratedTrades} />
        </section>
        <section className="column column-control">
          <AccountSummary
            account={userAccount}
            positions={positions}
            onRefresh={refreshAccount}
            refreshing={refreshingAccount}
          />
          <OrderForm
            onSubmitOrder={handleOrderSubmit}
            disabled={!authToken}
            defaultTicker={DEFAULT_TICKER}
          />
          <ScriptBuilder onSubmitScript={handleScriptSubmit} disabled={!authToken} />
          <ScriptUploader onSubmitScript={handleScriptSubmit} disabled={!authToken} />
          <OrdersTable orders={openOrders} />
          <FillsTable fills={fills} viewerId={userAccount?.userId ?? null} />
          <OrderLog events={orderEvents} />
        </section>
      </main>
    </div>
  );
}

export default AppRoot;
