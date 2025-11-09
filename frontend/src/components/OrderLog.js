import React from 'react';

const formatTime = (timestamp) => new Date(timestamp).toLocaleTimeString('en-GB', {
  hour12: false,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
});

function OrderLog({ events }) {
  if (!events.length) {
    return (
      <div className="event-log">
        <h3>Order Events</h3>
        <p>No orders yet.</p>
      </div>
    );
  }

  const items = [...events].reverse();

  return (
    <div className="event-log">
      <h3>Order Events</h3>
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Action</th>
            <th>Order ID</th>
            <th>Side</th>
            <th>Type</th>
            <th>Price</th>
            <th>Quantity</th>
          </tr>
        </thead>
        <tbody>
          {items.map((event, idx) => (
            <tr key={`${event.timestamp}-${event.order?.orderId ?? idx}`}>
              <td>{formatTime(event.timestamp)}</td>
              <td>{event.type}</td>
              <td>{event.order?.orderId ?? '—'}</td>
              <td>{event.order?.side ?? '—'}</td>
              <td>{event.order?.orderType ?? '—'}</td>
              <td>{event.order?.price ?? '—'}</td>
              <td>{event.order?.quantity ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default OrderLog;
