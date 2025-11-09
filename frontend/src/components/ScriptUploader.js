import React, { useState } from 'react';

function ScriptUploader({ onSubmitScript }) {
    const [scriptText, setScriptText] = useState('');
    const [showHelp, setShowHelp] = useState(false);

    const handleFileChange = (event) => {
        const file = event.target.files[0];
        if (file) {
            const reader = new FileReader();
            reader.onload = (e) => {
                setScriptText(e.target.result);
            };
            reader.readAsText(file);
        }
    };

    const handleSubmit = () => {
        const script = scriptText.split('\n').filter(line => line.trim() !== '');
        onSubmitScript(script);
    };

    return (
        <div className="script-uploader">
            <div className="script-uploader-header">
                <h3>Upload or Paste Script</h3>
                <button
                    type="button"
                    className="info-button"
                    onClick={() => setShowHelp((prev) => !prev)}
                    aria-expanded={showHelp}
                    aria-controls="script-format-help"
                >
                    i
                </button>
            </div>
            {showHelp && (
                <div className="script-help" id="script-format-help">
                    <p>Each line represents one command using space-delimited fields:</p>
                    <ul>
                        <li><strong>Add</strong>: <code>A B|S OrderType Price Quantity OrderId</code></li>
                        <li><strong>Modify</strong>: <code>M OrderId B|S Price Quantity</code></li>
                        <li><strong>Cancel</strong>: <code>R OrderId 0 0</code> or <code>C OrderId</code></li>
                        <li><strong>Market</strong> orders use price <code>0</code>; FillOrKill / FillAndKill map to FoK / FaK.</li>
                    </ul>
                    <p>Examples: <code>A B GoodTillCancel 101 50 1</code>, <code>M 2 S 102 40</code>.</p>
                </div>
            )}
            <textarea
                rows="10"
                cols="50"
                value={scriptText}
                onChange={(e) => setScriptText(e.target.value)}
                placeholder="Paste your script here or upload a file."
            />
            <div className="script-uploader-actions">
                <input type="file" accept=".txt" onChange={handleFileChange} style={{ display: 'none' }} id="file-input" />
                <label htmlFor="file-input" className="button-like">Load from File</label>
                <button onClick={handleSubmit} disabled={!scriptText.trim()}>Run Script</button>
            </div>
        </div>
    );
}

export default ScriptUploader;
