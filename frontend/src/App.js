import React, { useState, useEffect, useMemo } from 'react';
import './App.css';
import OrderForm from './components/OrderForm';
import OrderBook from './components/OrderBook';
import TradeHistory from './components/TradeHistory';
import ScriptBuilder from './components/ScriptBuilder';
import ScriptUploader from './components/ScriptUploader';
import OrderLog from './components/OrderLog';
import BestBidAskChart from './components/BestBidAskChart';

const appendWithLimit = (list, entry, limit) => {
  const next = [...list, entry];
  return next.length > limit ? next.slice(next.length - limit) : next;
};

function App() {
  const [orderBook, setOrderBook] = useState({ bids: [], asks: [] });
  const [trades, setTrades] = useState([]);
  const [orderEvents, setOrderEvents] = useState([]);
  const [priceHistory, setPriceHistory] = useState([]);
  const [isResetting, setIsResetting] = useState(false);
  // Store session data client-side so the UI can render timelines without extra round-trips.

  const recordOrderEvent = (event) => {
    setOrderEvents((prev) => appendWithLimit(prev, event, 2000));
  };

  const clearClientState = () => {
    setOrderBook({ bids: [], asks: [] });
    setTrades([]);
    setOrderEvents([]);
    setPriceHistory([]);
  };

  useEffect(() => {
    const resolveWebSocketBase = () => {
      const fallback = typeof window !== 'undefined' ? window.location.origin : 'http://localhost:7070';
      const raw = (process.env.REACT_APP_API_URL || fallback || 'http://localhost:7070').replace(/\/$/, '');

      if (raw.startsWith('http://')) {
        return `ws://${raw.substring('http://'.length)}`;
      }
      if (raw.startsWith('https://')) {
        return `wss://${raw.substring('https://'.length)}`;
      }
      if (raw.startsWith('ws://') || raw.startsWith('wss://')) {
        return raw;
      }
      return `ws://${raw}`;
    };

    const ws = new WebSocket(`${resolveWebSocketBase()}/ws/orderbook`);

    ws.onopen = () => {
      console.log('Connected to WebSocket');
    };
    const appendTrades = (tradePayload) => {
      if (!Array.isArray(tradePayload) || !tradePayload.length) {
        return;
      }

      const now = Date.now();
      const enriched = tradePayload.map((trade) => ({
        timestamp: now,
        price: trade?.bidTrade?.price ?? trade?.askTrade?.price ?? null,
        quantity: trade?.bidTrade?.quantity ?? trade?.askTrade?.quantity ?? null,
        bidOrderId: trade?.bidTrade?.orderId ?? null,
        askOrderId: trade?.askTrade?.orderId ?? null
      }));

      console.debug('[WS] Trades received', {
        rawCount: tradePayload.length,
        mappedCount: enriched.length,
        sample: enriched[0]
      });

      setTrades((prevTrades) => {
        const merged = [...prevTrades, ...enriched];
        return merged.length > 2000 ? merged.slice(merged.length - 2000) : merged;
      });
    };

    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);

      console.debug('[WS] Message payload', data);

      if (Array.isArray(data)) {
        if (data.length === 0) {
          console.debug('[WS] Received empty trades payload, clearing client state');
          clearClientState();
          return;
        }
        appendTrades(data);
        return;
      }

      if (data && Array.isArray(data.trades)) {
        if (data.trades.length === 0) {
          console.debug('[WS] Received empty trades array inside payload, clearing client state');
          clearClientState();
          return;
        }
        appendTrades(data.trades);
      }

      if (data && Array.isArray(data.bids) && Array.isArray(data.asks)) {
        setOrderBook(data);
        const now = Date.now();
        const bestBid = data.bids.length ? data.bids[0].price : null;
        const bestAsk = data.asks.length ? data.asks[0].price : null;
        console.debug('[WS] Order book snapshot', {
          bestBid,
          bestAsk,
          bidDepth: data.bids.length,
          askDepth: data.asks.length
        });
        setPriceHistory((prev) => appendWithLimit(prev, {
          time: now,
          bestBid,
          bestAsk
        }, 4000));
      } else {
        console.debug('[WS] Unhandled message shape', data);
      }
    };

    ws.onclose = () => {
      console.log('Disconnected from WebSocket');
    };

    ws.onerror = (event) => {
      console.error('WebSocket error', event);
    };

    return () => {
      ws.close();
    };
  }, []);

  const resetApplication = async () => {
    if (isResetting) {
      return;
    }

    setIsResetting(true);
    clearClientState();

    try {
      const response = await fetch('/api/reset', { method: 'POST' });
      if (!response.ok) {
        throw new Error(`Reset failed with status ${response.status}`);
      }
      console.log('Application reset succeeded');
    } catch (error) {
      console.error('Error resetting application:', error);
    } finally {
      setIsResetting(false);
    }
  };

  const resetButtonLabel = useMemo(() => (isResetting ? 'Resetting…' : 'Reset'), [isResetting]);

  const handleOrderSubmit = (order) => {
    recordOrderEvent({
      type: 'Add',
      timestamp: Date.now(),
      order
    });
    fetch('/api/order', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(order),
    });
  };

  const handleScriptSubmit = (script) => {
    script.forEach((line) => {
      const tokens = line.trim().split(/\s+/);
      if (!tokens[0]) {
        return;
      }
      const code = tokens[0].toUpperCase();
      const timestamp = Date.now();
      if (code === 'A' && tokens.length >= 6) {
        recordOrderEvent({
          type: 'Add',
          timestamp,
          order: {
            orderId: parseInt(tokens[5], 10),
            side: tokens[1] === 'B' ? 'Buy' : 'Sell',
            orderType: tokens[2],
            price: parseInt(tokens[3], 10),
            quantity: parseInt(tokens[4], 10)
          }
        });
      } else if (code === 'M' && tokens.length >= 5) {
        recordOrderEvent({
          type: 'Modify',
          timestamp,
          order: {
            orderId: parseInt(tokens[1], 10),
            side: tokens[2] === 'B' ? 'Buy' : 'Sell',
            orderType: 'MODIFY',
            price: parseInt(tokens[3], 10),
            quantity: parseInt(tokens[4], 10)
          }
        });
      } else if ((code === 'R' || code === 'C') && tokens.length >= 2) {
        recordOrderEvent({
          type: 'Cancel',
          timestamp,
          order: {
            orderId: parseInt(tokens[1], 10),
            orderType: 'CANCEL'
          }
        });
      }
    });
    fetch('/api/script', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(script),
    });
  };

  return (
    <div className="App">
      <header className="App-header">
          <div>
            <h1>Trade Tester</h1>
            <h2>Upload a trading strategy to test your strategy's market impact</h2>
          </div>
          <button
            type="button"
            className="reset-button"
            onClick={resetApplication}
            disabled={isResetting}
          >
            {resetButtonLabel}
          </button>
      </header>
      <main>
        <div className="left-panel">
          <OrderForm onSubmitOrder={handleOrderSubmit} />
          <ScriptBuilder onSubmitScript={handleScriptSubmit} />
          <ScriptUploader onSubmitScript={handleScriptSubmit} />
          <OrderLog events={orderEvents} />
        </div>
        <div className="right-panel">
          <OrderBook orderBook={orderBook} />
          <BestBidAskChart data={priceHistory} trades={trades} />
          <TradeHistory trades={trades} />
        </div>
      </main>
    </div>
  );
}

export default App;
