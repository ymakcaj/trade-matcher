import React, { useEffect, useState } from 'react';

function ConnectionBadge({ label, status }) {
  const className = `connection-badge connection-${status}`;
  return <span className={className}>{label}: {status}</span>;
}

function AuthPanel({ token, userId, onConnect, onDisconnect, publicStatus, privateStatus }) {
  const [inputToken, setInputToken] = useState(token ?? '');

  useEffect(() => {
    setInputToken(token ?? '');
  }, [token]);

  const handleSubmit = (event) => {
    event.preventDefault();
    const trimmed = inputToken.trim();
    if (!trimmed) {
      return;
    }
    onConnect(trimmed);
  };

  const handleDisconnect = () => {
    onDisconnect();
  };

  return (
    <div className="auth-panel">
      <form className="auth-form" onSubmit={handleSubmit}>
        <label htmlFor="api-token">API Token</label>
        <input
          id="api-token"
          type="text"
          value={inputToken}
          onChange={(event) => setInputToken(event.target.value)}
          placeholder="Paste API token"
          autoComplete="off"
        />
        <div className="auth-actions">
          <button type="submit" disabled={!inputToken.trim()}>Connect</button>
          <button type="button" onClick={handleDisconnect} disabled={!token}>Disconnect</button>
        </div>
      </form>
      <div className="auth-status">
        <div className="auth-user">{userId ? `Signed in as ${userId}` : 'Not authenticated'}</div>
        <div className="auth-connections">
          <ConnectionBadge label="Public feed" status={publicStatus} />
          <ConnectionBadge label="Private feed" status={privateStatus} />
        </div>
      </div>
    </div>
  );
}

export default AuthPanel;
