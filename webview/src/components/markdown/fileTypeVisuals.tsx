import React from 'react';

export type FileCategory =
  | 'kotlin'
  | 'java'
  | 'typescript'
  | 'javascript'
  | 'python'
  | 'mermaid'
  | 'config'
  | 'markdown'
  | 'sql'
  | 'style'
  | 'file';

export interface FileTypeVisual {
  category: FileCategory;
  badge: string;
  accentColor: string;
  bgTint: string;
  borderTint: string;
}

export const RECOGNIZED_FILE_EXTENSIONS = new Set([
  'kt',
  'kts',
  'java',
  'ts',
  'tsx',
  'js',
  'jsx',
  'mjs',
  'cjs',
  'py',
  'mmd',
  'json',
  'yaml',
  'yml',
  'toml',
  'xml',
  'gradle',
  'properties',
  'sql',
  'md',
  'mdx',
  'html',
  'css',
  'scss',
  'sh',
  'go',
  'rs'
]);

const EXTENSION_VISUAL_RULES: Array<{
  suffixes: string[];
  visual: FileTypeVisual | ((cleaned: string) => FileTypeVisual);
}> = [
  {
    suffixes: ['.kt', '.kts'],
    visual: {
      category: 'kotlin',
      badge: 'KT',
      accentColor: '#a855f7',
      bgTint: 'rgba(168, 85, 247, 0.14)',
      borderTint: 'rgba(168, 85, 247, 0.38)'
    }
  },
  {
    suffixes: ['.java'],
    visual: {
      category: 'java',
      badge: 'J',
      accentColor: '#f97316',
      bgTint: 'rgba(249, 115, 22, 0.14)',
      borderTint: 'rgba(249, 115, 22, 0.38)'
    }
  },
  {
    suffixes: ['.ts', '.tsx'],
    visual: (cleaned) => ({
      category: 'typescript',
      badge: cleaned.endsWith('.tsx') ? 'TSX' : 'TS',
      accentColor: '#3b82f6',
      bgTint: 'rgba(59, 130, 246, 0.15)',
      borderTint: 'rgba(59, 130, 246, 0.42)'
    })
  },
  {
    suffixes: ['.js', '.jsx', '.mjs', '.cjs'],
    visual: {
      category: 'javascript',
      badge: 'JS',
      accentColor: '#eab308',
      bgTint: 'rgba(234, 179, 8, 0.14)',
      borderTint: 'rgba(234, 179, 8, 0.38)'
    }
  },
  {
    suffixes: ['.py'],
    visual: {
      category: 'python',
      badge: 'PY',
      accentColor: '#38bdf8',
      bgTint: 'rgba(56, 189, 248, 0.14)',
      borderTint: 'rgba(56, 189, 248, 0.38)'
    }
  },
  {
    suffixes: ['.mmd'],
    visual: {
      category: 'mermaid',
      badge: 'ARC',
      accentColor: '#10b981',
      bgTint: 'rgba(16, 185, 129, 0.14)',
      borderTint: 'rgba(16, 185, 129, 0.38)'
    }
  },
  {
    suffixes: ['.json', '.yaml', '.yml', '.toml', '.xml', '.gradle', '.properties'],
    visual: {
      category: 'config',
      badge: 'CFG',
      accentColor: '#f59e0b',
      bgTint: 'rgba(245, 158, 11, 0.14)',
      borderTint: 'rgba(245, 158, 11, 0.38)'
    }
  },
  {
    suffixes: ['.md', '.mdx'],
    visual: {
      category: 'markdown',
      badge: 'MD',
      accentColor: '#60a5fa',
      bgTint: 'rgba(96, 165, 250, 0.14)',
      borderTint: 'rgba(96, 165, 250, 0.38)'
    }
  },
  {
    suffixes: ['.sql'],
    visual: {
      category: 'sql',
      badge: 'SQL',
      accentColor: '#14b8a6',
      bgTint: 'rgba(20, 184, 166, 0.14)',
      borderTint: 'rgba(20, 184, 166, 0.38)'
    }
  },
  {
    suffixes: ['.html', '.css', '.scss'],
    visual: {
      category: 'style',
      badge: 'UI',
      accentColor: '#ec4899',
      bgTint: 'rgba(236, 72, 153, 0.14)',
      borderTint: 'rgba(236, 72, 153, 0.38)'
    }
  }
];

const DEFAULT_FILE_VISUAL: FileTypeVisual = {
  category: 'file',
  badge: 'FILE',
  accentColor: '#94a3b8',
  bgTint: 'rgba(148, 163, 184, 0.14)',
  borderTint: 'rgba(148, 163, 184, 0.35)'
};

export function getFileTypeVisual(filePathOrName: string): FileTypeVisual {
  const cleaned = filePathOrName
    .trim()
    .replace(/^file:\/\//, '')
    .split('#')[0]
    .replace(/:\d+(?::\d+)?$/, '')
    .toLowerCase();

  for (const rule of EXTENSION_VISUAL_RULES) {
    if (rule.suffixes.some((suffix) => cleaned.endsWith(suffix))) {
      return typeof rule.visual === 'function' ? rule.visual(cleaned) : rule.visual;
    }
  }
  return DEFAULT_FILE_VISUAL;
}

export const FileTypeIcon: React.FC<{ visual: FileTypeVisual; size?: number }> = ({
  visual,
  size = 13
}) => {
  const { category, accentColor } = visual;

  switch (category) {
    case 'kotlin':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <path d="M2.5 2.5H13.5L8 8L13.5 13.5H2.5V2.5Z" fill={accentColor} />
          <path d="M2.5 2.5H8L2.5 8V2.5Z" fill="#f97316" />
        </svg>
      );
    case 'java':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <rect x="2" y="2" width="12" height="12" rx="2.5" fill="rgba(249, 115, 22, 0.22)" stroke={accentColor} strokeWidth="1.3" />
          <path d="M9.5 4.5V9.2C9.5 10.6 8.6 11.4 7.3 11.4C6.3 11.4 5.7 10.9 5.5 10.2" stroke={accentColor} strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      );
    case 'typescript':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <rect x="1.5" y="1.5" width="13" height="13" rx="2.5" fill={accentColor} />
          <path d="M4.2 6.2H8.4M6.3 6.2V11.8" stroke="#ffffff" strokeWidth="1.4" strokeLinecap="round" />
          <path d="M12.2 7.1C11.9 6.5 11.3 6.2 10.6 6.2C9.7 6.2 9.1 6.7 9.1 7.4C9.1 8.9 12.3 8.5 12.3 10.3C12.3 11.2 11.5 11.8 10.4 11.8C9.6 11.8 8.9 11.4 8.6 10.8" stroke="#ffffff" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case 'javascript':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <rect x="1.5" y="1.5" width="13" height="13" rx="2.5" fill={accentColor} />
          <path d="M7.2 6V10.4C7.2 11.3 6.6 11.8 5.7 11.8C5.1 11.8 4.6 11.5 4.4 11" stroke="#09090b" strokeWidth="1.4" strokeLinecap="round" />
          <path d="M12.2 7.1C11.9 6.5 11.3 6.2 10.6 6.2C9.7 6.2 9.1 6.7 9.1 7.4C9.1 8.9 12.3 8.5 12.3 10.3C12.3 11.2 11.5 11.8 10.4 11.8C9.6 11.8 8.9 11.4 8.6 10.8" stroke="#09090b" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case 'python':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <path d="M7.8 2C5.5 2 5.2 3 5.2 4.2V5.5H8.2V6H4.2C2.9 6 2 6.9 2 8.5C2 10.1 2.9 11 4.2 11H5.2V9.6C5.2 8.5 6.1 7.7 7.2 7.7H9.8C10.6 7.7 11.2 7.1 11.2 6.3V4.2C11.2 2.9 10.1 2 7.8 2Z" fill="#38bdf8" />
          <path d="M8.2 14C10.5 14 10.8 13 10.8 11.8V10.5H7.8V10H11.8C13.1 10 14 9.1 14 7.5C14 5.9 13.1 5 11.8 5H10.8V6.4C10.8 7.5 9.9 8.3 8.8 8.3H6.2C5.4 8.3 4.8 8.9 4.8 9.7V11.8C4.8 13.1 5.9 14 8.2 14Z" fill="#facc15" />
        </svg>
      );
    case 'mermaid':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <rect x="2" y="2" width="4.5" height="4.5" rx="1" stroke={accentColor} strokeWidth="1.4" />
          <rect x="9.5" y="2" width="4.5" height="4.5" rx="1" stroke={accentColor} strokeWidth="1.4" />
          <rect x="5.75" y="9.5" width="4.5" height="4.5" rx="1" stroke={accentColor} strokeWidth="1.4" />
          <path d="M4.25 6.5V8H11.75V6.5M8 8V9.5" stroke={accentColor} strokeWidth="1.3" strokeLinecap="round" />
        </svg>
      );
    case 'config':
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <path d="M5.5 3C4.2 3 3.8 3.7 3.8 4.8V6.4C3.8 7.2 3.2 7.8 2.4 7.8C3.2 7.8 3.8 8.4 3.8 9.2V10.8C3.8 11.9 4.2 12.6 5.5 12.6" stroke={accentColor} strokeWidth="1.4" strokeLinecap="round" />
          <path d="M10.5 3C11.8 3 12.2 3.7 12.2 4.8V6.4C12.2 7.2 12.8 7.8 13.6 7.8C12.8 7.8 12.2 8.4 12.2 9.2V10.8C12.2 11.9 11.8 12.6 10.5 12.6" stroke={accentColor} strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      );
    default:
      return (
        <svg width={size} height={size} viewBox="0 0 16 16" fill="none" style={{ flexShrink: 0 }}>
          <path d="M4 2H9.8L12.5 4.7V13.2C12.5 13.6 12.1 14 11.7 14H4C3.5 14 3.2 13.6 3.2 13.2V2.8C3.2 2.4 3.5 2 4 2Z" stroke={accentColor} strokeWidth="1.3" />
          <path d="M9.5 2V5H12.5" stroke={accentColor} strokeWidth="1.3" strokeLinejoin="round" />
        </svg>
      );
  }
};
