import React, { useEffect, useState } from 'react';
import { BoundaryRule, RulePolicy } from '../../types/bridge';
import { MONO_FONT } from '../markdown/constants';

export interface BoundaryRuleModalProps {
  existingRules: BoundaryRule[];
  onSaveRule: (rule: BoundaryRule) => void;
  onClose: () => void;
}

const fieldStyle: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  background: '#09090b',
  color: '#f4f4f5',
  border: '1px solid #3f3f46',
  borderRadius: 6,
  padding: '6px 9px',
  fontSize: 12,
  fontFamily: MONO_FONT
};

export const BoundaryRuleModal: React.FC<BoundaryRuleModalProps> = ({
  existingRules,
  onSaveRule,
  onClose
}) => {
  const [ruleId, setRuleId] = useState(() => {
    const taken = new Set(existingRules.map((r) => r.id));
    let n = existingRules.length + 1;
    while (taken.has(`rule-${n}`)) {
      n++;
    }
    return `rule-${n}`;
  });
  const [name, setName] = useState('No Direct Controller to Repository Dependency');
  const [fromPattern, setFromPattern] = useState('*.controller.*');
  const [toPattern, setToPattern] = useState('*.repository.*');
  const [policy, setPolicy] = useState<RulePolicy>('FORBIDDEN');
  const [rationale, setRationale] = useState('Enforced via ArcDrift Spec Canvas & ArchUnit.');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const cleanId = ruleId.trim();
    const cleanFrom = fromPattern.trim();
    const cleanTo = toPattern.trim();
    if (!cleanId || !cleanFrom || !cleanTo) {
      setError('Rule ID, From Pattern, and To Pattern are required.');
      return;
    }
    onSaveRule({
      id: cleanId,
      name: name.trim() || cleanId,
      fromPattern: cleanFrom,
      toPattern: cleanTo,
      policy,
      rationale: rationale.trim() || 'Defined in ArcDrift Canvas.'
    });
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Add Boundary Rule"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(9, 9, 11, 0.72)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 50,
        padding: 16
      }}
      onClick={onClose}
    >
      <form
        onSubmit={handleSubmit}
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 480,
          maxWidth: '94vw',
          maxHeight: '88vh',
          overflowY: 'auto',
          background: '#121215',
          border: '1px solid #3f3f46',
          borderRadius: 10,
          padding: 18,
          color: '#f4f4f5',
          display: 'flex',
          flexDirection: 'column',
          gap: 12
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 600 }}>Add Boundary Rule</h3>
          <button type="button" className="arc-btn" onClick={onClose} style={{ padding: '2px 8px' }}>
            ✕
          </button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 140px', gap: 10 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
            Rule ID
            <input value={ruleId} onChange={(e) => setRuleId(e.target.value)} style={fieldStyle} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
            Policy
            <select
              value={policy}
              onChange={(e) => setPolicy(e.target.value as RulePolicy)}
              style={fieldStyle}
            >
              <option value="FORBIDDEN">FORBIDDEN</option>
              <option value="ALLOWED_ONLY">ALLOWED_ONLY</option>
              <option value="MUST_DEPEND_VIA_INTERFACE">MUST_DEPEND_VIA_INTERFACE</option>
            </select>
          </label>
        </div>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
          Rule Name
          <input value={name} onChange={(e) => setName(e.target.value)} style={fieldStyle} />
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
            From Pattern (glob)
            <input value={fromPattern} onChange={(e) => setFromPattern(e.target.value)} style={fieldStyle} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
            To Pattern (glob)
            <input value={toPattern} onChange={(e) => setToPattern(e.target.value)} style={fieldStyle} />
          </label>
        </div>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a1a1aa' }}>
          Rationale
          <input value={rationale} onChange={(e) => setRationale(e.target.value)} style={fieldStyle} />
        </label>

        {error && (
          <div style={{ fontSize: 12, color: '#fca5a5' }}>{error}</div>
        )}

        {existingRules.length > 0 && (
          <div style={{ borderTop: '1px solid #27272a', paddingTop: 10 }}>
            <div style={{ fontSize: 11.5, fontWeight: 600, color: '#a1a1aa', marginBottom: 6 }}>
              Active Guardrails ({existingRules.length})
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 110, overflowY: 'auto' }}>
              {existingRules.map((r) => (
                <div
                  key={r.id}
                  style={{
                    fontSize: 11,
                    fontFamily: MONO_FONT,
                    color: '#d4d4d8',
                    background: '#09090b',
                    border: '1px solid #27272a',
                    borderRadius: 5,
                    padding: '4px 8px'
                  }}
                >
                  [{r.policy}] {r.id}: {r.fromPattern} → {r.toPattern}
                </div>
              ))}
            </div>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 4 }}>
          <button type="button" className="arc-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="arc-btn arc-btn-primary">
            Save Rule to Spec
          </button>
        </div>
      </form>
    </div>
  );
};
