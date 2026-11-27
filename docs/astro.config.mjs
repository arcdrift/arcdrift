import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import starlight from '@astrojs/starlight';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';

export default defineConfig({
  site: 'https://docs.arcdrift.dev',
  markdown: {
    processor: unified({
      remarkPlugins: [remarkMath],
      rehypePlugins: [rehypeKatex]
    })
  },
  integrations: [
    starlight({
      title: 'ArcDrift',
      description: 'Live C4 Architecture Canvas, Polyglot Contract Guardrails & Self-Healing Agent for IntelliJ IDEA and CI/CD',
      favicon: '/favicon.svg',
      head: [
        {
          tag: 'meta',
          attrs: { property: 'og:image', content: 'https://docs.arcdrift.dev/og-card.svg' }
        },
        {
          tag: 'meta',
          attrs: { name: 'twitter:image', content: 'https://docs.arcdrift.dev/og-card.svg' }
        }
      ],
      logo: {
        light: './src/assets/logo-light.svg',
        dark: './src/assets/logo-dark.svg'
      },
      customCss: ['katex/dist/katex.min.css', './src/styles/custom.css'],
      expressiveCode: {
        defaultProps: {
          wrap: false
        }
      },
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/arcdrift/arcdrift'
        }
      ],
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { label: 'Welcome to ArcDrift', slug: 'index' },
            { label: 'Installation & Setup', slug: 'getting-started/installation' },
            { label: '5-Minute Quickstart', slug: 'getting-started/quickstart' }
          ]
        },
        {
          label: 'Core Concepts',
          items: [
            { label: 'How ArcDrift Works', slug: 'concepts/architecture-overview' },
            { label: 'Navigating C4 Zoom Levels', slug: 'concepts/c4-semantic-zoom' },
            { label: 'Defining Guardrails (.arc.mmd)', slug: 'concepts/mermaid-arc-spec' },
            { label: 'Benchmarks & Accuracy', slug: 'concepts/benchmarks-and-accuracy' }
          ]
        },
        {
          label: 'Guides & Workflows',
          items: [
            { label: 'Catching REST & Event Drift', slug: 'guides/polyglot-contracts' },
            { label: 'Using the Self-Healing Agent', slug: 'guides/self-healing-agent' },
            { label: 'Refactoring from the Canvas', slug: 'guides/deterministic-refactoring' },
            { label: 'CI/CD Checks & PR Diffs', slug: 'guides/ci-cd-and-archunit' },
            { label: 'Multi-Repo Federation, SARIF & SonarQube', slug: 'guides/multi-repo-federation' },
            { label: 'Sample Projects to Try', slug: 'guides/open-source-testbeds' }
          ]
        },
        {
          label: 'Reference',
          items: [
            { label: 'arc CLI Reference', slug: 'reference/cli-reference' },
            { label: 'MCP Tools for AI Assistants', slug: 'reference/mcp-server-tools' },
            { label: 'UI Controls & Agent Settings', slug: 'reference/ide-bridge-protocol' },
            { label: 'Glossary & Architectural Metrics', slug: 'reference/glossary-and-metrics' },
            { label: 'Troubleshooting & FAQ', slug: 'reference/troubleshooting' }
          ]
        }
      ]
    })
  ]
});
