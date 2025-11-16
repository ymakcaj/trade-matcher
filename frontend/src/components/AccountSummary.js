import React from 'react';

function AccountSummary({ account, positions, onRefresh, refreshing }) {
  if (!account) {
    return (
      <div className="account-summary">
        <h3>Account</h3>
        <p>Authenticate to view balances and positions.</p>
      </div>
    );
  }

  const formatCash = (value) => {
    if (typeof value !== 'number') {
      return value;
    }
    return value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  };

  return (
    <div className="account-summary">
      <div className="account-header">
        <h3>Account</h3>
        <button type="button" onClick={onRefresh} disabled={refreshing}>Refresh</button>
      </div>
      <div className="account-details">
        <div><strong>User:</strong> {account.userId}</div>
        <div><strong>Cash:</strong> {formatCash(account.cash)}</div>
      </div>
      <div className="account-positions">
        <h4>Positions</h4>
        {(!positions || positions.length === 0) ? (
          <p className="empty">No positions.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Ticker</th>
                <th>Quantity</th>
              </tr>
            </thead>
            <tbody>
              {positions.map((position) => (
                <tr key={position.ticker}>
                  <td>{position.ticker}</td>
                  <td>{position.quantity}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

export default AccountSummary;
