import React, { useState } from 'react';
import { ArcNode } from '../../types/bridge';
import { MONO_FONT } from './constants';
import { OpenFileTarget, parseFileReference } from './fileReferences';
import { FileTypeIcon, getFileTypeVisual } from './fileTypeVisuals';
import { FileChip } from './FileChip';

const LANG_TO_STUB: Record<string, string> = {
  kotlin: 'snippet.kt',
  kt: 'snippet.kt',
  java: 'snippet.java',
  typescript: 'snippet.ts',
  ts: 'snippet.ts',
  tsx: 'snippet.ts',
  javascript: 'snippet.js',
  js: 'snippet.js',
  python: 'snippet.py',
  py: 'snippet.py',
  mermaid: 'snippet.mmd',
  mmd: 'snippet.mmd',
  json: 'snippet.json',
  yaml: 'snippet.json',
  yml: 'snippet.json'
};

export interface CodeBlockProps {
  language: string;
  code: string;
  nodes: ArcNode[];
  onOpenFile?: (target: OpenFileTarget) => void;
}

export const MarkdownCodeBlock: React.FC<CodeBlockProps> = ({
  language,
  code,
  nodes,
  onOpenFile
}) => {
  const [copied, setCopied] = useState(false);
  const parts = language.trim().split(/\s+/);
  const langName = parts[0] || 'code';
  const fileCandidate = parts.slice(1).join(' ') || (langName.includes('.') ? langName : '');
  const headerFileRef = fileCandidate ? parseFileReference(fileCandidate, nodes) : null;
  const visual = getFileTypeVisual(LANG_TO_STUB[langName] ?? 'snippet.txt');

  const handleCopy = () => {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(code).catch(() => {});
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div
      style={{
        margin: '8px 0',
        borderRadius: 7,
        border: '1px solid #27272a',
        background: '#09090b',
        overflow: 'hidden'
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '4px 10px',
          background: '#141418',
          borderBottom: '1px solid #27272a',
          fontSize: 11,
          color: '#a1a1aa'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {headerFileRef ? (
            <FileChip reference={headerFileRef} onOpenFile={onOpenFile} />
          ) : (
            <>
              <FileTypeIcon visual={visual} size={12} />
              <span style={{ fontFamily: MONO_FONT, fontWeight: 600, color: '#d4d4d8' }}>
                {langName}
              </span>
            </>
          )}
        </div>
        <button
          type="button"
          onClick={handleCopy}
          style={{
            background: 'transparent',
            border: '1px solid #3f3f46',
            borderRadius: 4,
            color: copied ? '#86efac' : '#a1a1aa',
            padding: '1px 7px',
            fontSize: 10.5,
            cursor: 'pointer',
            fontFamily: MONO_FONT
          }}
        >
          {copied ? '✓ Copied' : 'Copy'}
        </button>
      </div>
      <pre
        style={{
          margin: 0,
          padding: '10px 12px',
          fontFamily: MONO_FONT,
          fontSize: 11.5,
          lineHeight: 1.5,
          color: '#e4e4e7',
          overflowX: 'auto',
          whiteSpace: 'pre'
        }}
      >
        <code>{code}</code>
      </pre>
    </div>
  );
};
