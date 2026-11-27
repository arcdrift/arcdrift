import React from 'react';
import { MONO_FONT } from '../markdown/constants';

export interface AgentConfigFormProps {
  baseUrl: string;
  model: string;
  models: string[];
  apiKey: string;
  hasSavedApiKey?: boolean;
  maxTurns: number;
  promptText: string;
  onChangeBaseUrl: (value: string) => void;
  onChangeModel: (value: string) => void;
  onChangeApiKey: (value: string) => void;
  onChangeMaxTurns: (value: number) => void;
  onChangePromptText: (value: string) => void;
  onFetchModels: () => void;
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  boxSizing: 'border-box',
  background: '#09090b',
  color: '#f4f4f5',
  border: '1px solid #3f3f46',
  borderRadius: 6,
  padding: '5px 8px',
  fontSize: 12,
  fontFamily: MONO_FONT
};

export const AgentConfigForm: React.FC<AgentConfigFormProps> = ({
  baseUrl,
  model,
  models,
  apiKey,
  hasSavedApiKey,
  maxTurns,
  promptText,
  onChangeBaseUrl,
  onChangeModel,
  onChangeApiKey,
  onChangeMaxTurns,
  onChangePromptText,
  onFetchModels
}) => (
  <div
    style={{
      padding: '12px 14px',
      borderBottom: '1px solid #27272a',
      background: '#121215',
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }}
  >
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
      <label style={{ fontSize: 12, color: '#a1a1aa', fontWeight: 500 }}>
        OpenAI API Base URL
      </label>
      <div style={{ display: 'flex', gap: 6, minWidth: 0 }}>
        <input
          type="text"
          value={baseUrl}
          onChange={(e) => onChangeBaseUrl(e.target.value)}
          placeholder="http://localhost:11434"
          style={{ ...inputStyle, flex: 1, padding: '5px 10px' }}
        />
        <button
          type="button"
          className="arc-btn"
          style={{ flexShrink: 0, whiteSpace: 'nowrap' }}
          onClick={onFetchModels}
        >
          Fetch Models
        </button>
      </div>
    </div>

    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
      <label style={{ fontSize: 12, color: '#a1a1aa', fontWeight: 500 }}>
        Model ID
      </label>
      <div style={{ display: 'flex', gap: 6, minWidth: 0, width: '100%' }}>
        <input
          type="text"
          value={model}
          onChange={(e) => onChangeModel(e.target.value)}
          placeholder="Model ID (or auto-select first)"
          style={{ ...inputStyle, flex: 1, padding: '5px 10px' }}
        />
        {models.length > 0 && (
          <select
            value={models.includes(model) ? model : ''}
            onChange={(e) => {
              if (e.target.value) onChangeModel(e.target.value);
            }}
            title="Select a model discovered from /v1/models"
            style={{
              width: 175,
              maxWidth: '46%',
              minWidth: 0,
              flexShrink: 0,
              boxSizing: 'border-box',
              background: '#09090b',
              color: '#f4f4f5',
              border: '1px solid #3f3f46',
              borderRadius: 6,
              padding: '5px 8px',
              fontSize: 12,
              cursor: 'pointer',
              overflow: 'hidden',
              textOverflow: 'ellipsis'
            }}
          >
            <option value="">
              {models.length === 1 ? '1 Model…' : `Models (${models.length})…`}
            </option>
            {models.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        )}
      </div>
    </div>

    <div style={{ display: 'flex', gap: 8, minWidth: 0, width: '100%' }}>
      <div style={{ width: 110, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <label style={{ fontSize: 12, color: '#a1a1aa', fontWeight: 500 }}>
          Max Turns
        </label>
        <input
          type="number"
          min={1}
          max={100}
          value={maxTurns}
          onChange={(e) => {
            const parsed = parseInt(e.target.value, 10);
            const v = Number.isFinite(parsed) && parsed >= 1 ? Math.min(100, parsed) : 1;
            onChangeMaxTurns(v);
          }}
          style={inputStyle}
        />
      </div>

      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <label style={{ fontSize: 12, color: '#a1a1aa', fontWeight: 500 }}>
          API Key (optional)
        </label>
        <input
          type="password"
          value={apiKey}
          onChange={(e) => onChangeApiKey(e.target.value)}
          placeholder={hasSavedApiKey ? 'Saved in IDE PasswordSafe (enter new key to replace)' : 'Bearer token'}
          style={inputStyle}
        />
      </div>
    </div>

    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label style={{ fontSize: 12, color: '#a1a1aa', fontWeight: 500 }}>
        Architectural Task Prompt
      </label>
      <textarea
        rows={3}
        value={promptText}
        onChange={(e) => onChangePromptText(e.target.value)}
        style={{
          width: '100%',
          background: '#09090b',
          color: '#e4e4e7',
          border: '1px solid #3f3f46',
          borderRadius: 6,
          padding: '8px',
          fontSize: 12,
          fontFamily: MONO_FONT,
          resize: 'vertical'
        }}
      />
    </div>
  </div>
);
