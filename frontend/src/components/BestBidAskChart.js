import React, { useMemo } from 'react';
import {
  ResponsiveContainer,
  ComposedChart,
  Line,
  Scatter,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip
} from 'recharts';

const formatTime = (time) => new Date(time).toLocaleTimeString('en-GB', {
  hour12: false,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
});

const DotMarker = ({ cx, cy, fill = '#111', radius = 3 }) => {
  if (typeof cx !== 'number' || typeof cy !== 'number') {
    return null;
  }

  return <circle cx={cx} cy={cy} r={radius} fill={fill} />;
};

function BestBidAskChart({ data, trades = [] }) {
  const chartData = useMemo(() => data.map((entry) => ({
    ...entry,
    x: entry.time
  })), [data]);

  const tradeMarkers = useMemo(() => trades
    .filter((trade) => typeof trade.price === 'number' && typeof trade.timestamp === 'number')
    .map((trade) => ({
      x: trade.timestamp,
      y: trade.price,
      quantity: trade.quantity,
      bidOrderId: trade.bidOrderId,
      askOrderId: trade.askOrderId
    })), [trades]);

  if (!chartData.length) {
    return (
      <div className="chart-container">
        <h3>Best Bid / Ask</h3>
        <p>No market data yet.</p>
      </div>
    );
  }

  const renderTooltip = ({ active, payload }) => {
    if (!active || !payload || !payload.length) {
      return null;
    }

    const first = payload[0];
    if (first && first.name === 'Trades') {
      const trade = first.payload;
      return (
        <div className="chart-tooltip">
          <strong>Trade</strong>
          <div>Time: {formatTime(trade.x)}</div>
          <div>Price: {trade.y}</div>
          <div>Quantity: {trade.quantity ?? '—'}</div>
          <div>Buy Order: {trade.bidOrderId ?? '—'}</div>
          <div>Sell Order: {trade.askOrderId ?? '—'}</div>
        </div>
      );
    }

    const bid = payload.find((entry) => entry.dataKey === 'bestBid');
    const ask = payload.find((entry) => entry.dataKey === 'bestAsk');
    const base = first.payload;
    return (
      <div className="chart-tooltip">
        <div>Time: {formatTime(base.x)}</div>
        {bid && <div>Best Bid: {bid.value}</div>}
        {ask && <div>Best Ask: {ask.value}</div>}
      </div>
    );
  };

  return (
    <div className="chart-container">
      <h3>Best Bid / Ask</h3>
      <ResponsiveContainer width="100%" height={250}>
        <ComposedChart data={chartData}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis
            dataKey="x"
            type="number"
            domain={[
              (dataMin) => (typeof dataMin === 'number' ? dataMin - 1000 : 'auto'),
              (dataMax) => (typeof dataMax === 'number' ? dataMax + 1000 : 'auto')
            ]}
            tickFormatter={formatTime}
            minTickGap={20}
          />
          <YAxis type="number" domain={["auto", "auto"]} />
          <Tooltip content={renderTooltip} />
          <Line type="monotone" dataKey="bestBid" stroke="#ffd447" dot isAnimationActive={false} />
          <Line type="monotone" dataKey="bestAsk" stroke="#dc3545" dot isAnimationActive={false} />
          <Scatter
            name="Trades"
            data={tradeMarkers}
            dataKey="y"
            fill="#111"
            shape={(props) => <DotMarker {...props} />}
            isAnimationActive={false}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

export default BestBidAskChart;
