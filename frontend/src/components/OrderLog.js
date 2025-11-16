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
            <th>Status</th>
            <th>Order ID</th>
            <th>Side</th>
            <th>Type</th>
            <th>Price</th>
            <th>Quantity</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {items.map((event, idx) => (
            <tr key={`${event.timestamp}-${event.orderId ?? idx}`} className={event.severity ? `event-${event.severity}` : ''}>
              <td>{formatTime(event.timestamp)}</td>
              <td>{event.phase}</td>
              <td>{event.orderId ?? '—'}</td>
              <td>{event.side ?? '—'}</td>
              <td>{event.orderType ?? '—'}</td>
              <td>{event.price ?? '—'}</td>
              <td>{event.quantity ?? '—'}</td>
              <td>{event.message ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default OrderLog;
