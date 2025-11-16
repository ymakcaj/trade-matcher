import React from 'react';

const formatTime = (value) => {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

function FillsTable({ fills, viewerId }) {
  return (
    <div className="fills-table">
      <h3>Recent Fills</h3>
      {(!fills || fills.length === 0) ? (
        <p className="empty">No fills yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Order</th>
              <th>Ticker</th>
              <th>Side</th>
              <th>Price</th>
              <th>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {fills.map((fill) => (
              <tr key={fill.fillId} className={fill.userId === viewerId ? 'fill-own' : ''}>
                <td>{formatTime(fill.timestamp)}</td>
                <td>{fill.orderId}</td>
                <td>{fill.ticker}</td>
                <td className={`side-${fill.side?.toLowerCase()}`}>{fill.side}</td>
                <td>{typeof fill.price === 'number' ? fill.price.toFixed(3) : fill.price}</td>
                <td>{fill.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default FillsTable;
