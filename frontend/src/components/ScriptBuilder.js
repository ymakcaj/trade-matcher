import React, { useState } from 'react';

function ScriptBuilder({ onSubmitScript, disabled }) {
    const [script, setScript] = useState([]);
    const [command, setCommand] = useState('A');
    const [side, setSide] = useState('Buy');
    const [orderType, setOrderType] = useState('LIMIT');
    const [price, setPrice] = useState('');
    const [quantity, setQuantity] = useState('');
    const [orderId, setOrderId] = useState('');

    const handleAddCommand = () => {
        if (disabled) {
            return;
        }
        let commandString = `${command} ${side === 'Buy' ? 'B' : 'S'} ${orderType} ${price} ${quantity} ${orderId}`;
        if (command === 'C') {
            commandString = `${command} ${orderId}`;
        } else if (command === 'M') {
            commandString = `${command} ${orderId} ${side === 'Buy' ? 'B' : 'S'} ${price} ${quantity}`;
        }
        setScript([...script, commandString]);
        // Clear fields
        setPrice('');
        setQuantity('');
        setOrderId('');
    };

    const handleSubmitScript = () => {
        if (disabled) {
            return;
        }
        onSubmitScript(script);
        setScript([]);
    };

    return (
        <div className="script-builder">
            <h3>Script Builder</h3>
            <div className="script-controls">
                <select value={command} onChange={(e) => setCommand(e.target.value)} disabled={disabled}>
                    <option value="A">Add</option>
                    <option value="C">Cancel</option>
                    <option value="M">Modify</option>
                </select>
                {command !== 'C' && (
                    <>
                                <select value={side} onChange={(e) => setSide(e.target.value)} disabled={disabled}>
                            <option value="Buy">Buy</option>
                            <option value="Sell">Sell</option>
                        </select>
                        {command === 'A' && (
                                    <select value={orderType} onChange={(e) => setOrderType(e.target.value)} disabled={disabled}>
                                <option value="LIMIT">LIMIT</option>
                                <option value="MARKET">MARKET</option>
                                <option value="FILL_OR_KILL">FILL_OR_KILL</option>
                            </select>
                        )}
                                <input type="number" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="Price" disabled={disabled} />
                                <input type="number" value={quantity} onChange={(e) => setQuantity(e.target.value)} placeholder="Quantity" disabled={disabled} />
                    </>
                )}
                        <input type="number" value={orderId} onChange={(e) => setOrderId(e.target.value)} placeholder="Order ID" required disabled={disabled} />

                        <button onClick={handleAddCommand} type="button" disabled={disabled}>Add to Script</button>
            </div>
            <div className="script-preview">
                <h4>Script Preview</h4>
                <ul>
                    {script.map((line, index) => (
                        <li key={index}>{line}</li>
                    ))}
                </ul>
            </div>
            <button onClick={handleSubmitScript} disabled={script.length === 0 || disabled}>
                Run Script
            </button>
        </div>
    );
}

export default ScriptBuilder;
