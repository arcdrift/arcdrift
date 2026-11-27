import React, { useState } from 'react';
import { MONO_FONT } from './constants';
import { FileReference, OpenFileTarget } from './fileReferences';
import { FileTypeIcon } from './fileTypeVisuals';

export const FileChip: React.FC<{
  reference: FileReference;
  onOpenFile?: (target: OpenFileTarget) => void;
}> = ({ reference, onOpenFile }) => {
  const [hovered, setHovered] = useState(false);
  const { displayName, filePath, line, lineLabel, nodeId, visual } = reference;

  const tooltip = line
    ? `Open ${filePath} (line ${line}) in IDE editor`
    : `Open ${filePath} in IDE editor`;

  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onOpenFile?.({ filePath, line, nodeId });
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      title={tooltip}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        padding: '1px 7px 1px 5px',
        margin: '0 2px',
        borderRadius: 5,
        background: hovered ? 'rgba(59, 130, 246, 0.22)' : visual.bgTint,
        border: `1px solid ${hovered ? '#60a5fa' : visual.borderTint}`,
        color: '#f4f4f5',
        fontFamily: MONO_FONT,
        fontSize: 11.5,
        fontWeight: 500,
        lineHeight: 1.45,
        cursor: 'pointer',
        verticalAlign: 'baseline',
        transition: 'background 0.12s ease, border-color 0.12s ease',
        maxWidth: '100%'
      }}
    >
      <FileTypeIcon visual={visual} size={12} />
      <span
        style={{
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          color: hovered ? '#ffffff' : '#e4e4e7'
        }}
      >
        {displayName}
      </span>
      {lineLabel && !displayName.endsWith(lineLabel) && (
        <span
          style={{
            color: '#93c5fd',
            fontSize: 10.5,
            fontWeight: 600
          }}
        >
          {lineLabel}
        </span>
      )}
    </button>
  );
};
