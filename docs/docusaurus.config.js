// @ts-check

const lightCodeTheme = require('prism-react-renderer').themes.github;
const darkCodeTheme = require('prism-react-renderer').themes.dracula;

const siteUrl = process.env.DOCUSAURUS_URL || 'https://alpaca.markets';
const siteBaseUrl = process.env.DOCUSAURUS_BASE_URL || '/alpaca-java/';

function footerProjectLink(label, href, iconClassName) {
  return {
    html: `<a class="footer__link-item footer-project-link" href="${href}" target="_blank" rel="noopener noreferrer"><i class="${iconClassName} footer-project-link__icon" aria-hidden="true"></i><span>${label}</span></a>`,
  };
}

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Alpaca Java Client',
  tagline: 'Java SDK documentation for Alpaca Broker, Market Data, Trading, and streaming APIs.',
  favicon: 'img/alpaca-symbol-yellow.png',

  url: siteUrl,
  baseUrl: siteBaseUrl,

  organizationName: 'alpacahq',
  projectName: 'alpaca-java',

  stylesheets: [
    {
      href: 'https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@7.2.0/css/fontawesome.min.css',
      type: 'text/css',
      crossorigin: 'anonymous',
    },
    {
      href: 'https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@7.2.0/css/brands.min.css',
      type: 'text/css',
      crossorigin: 'anonymous',
    },
  ],

  onBrokenLinks: 'throw',
  onBrokenAnchors: 'throw',
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: 'content',
          routeBasePath: '/',
          sidebarPath: require.resolve('./sidebars.js'),
        },
        blog: false,
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      }),
    ],
  ],

  themes: [
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        docsDir: 'content',
        docsRouteBasePath: '/',
        hashed: 'filename',
        indexBlog: false,
        indexDocs: true,
        indexPages: false,
        language: ['en'],
        removeDefaultStopWordFilter: true,
        searchBarPosition: 'right',
        searchResultContextMaxLength: 80,
        searchResultLimits: 8,
      },
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      navbar: {
        title: 'Alpaca Java Client',
        logo: {
          alt: 'Alpaca',
          src: 'img/alpaca-symbol-yellow.png',
          width: 32,
          height: 32,
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'wikiSidebar',
            position: 'left',
            label: 'Wiki',
          },
          {
            to: '/api',
            label: 'API Reference',
            position: 'left',
          },
          {
            href: 'https://github.com/alpacahq/alpaca-java',
            html: '<i class="fa-brands fa-github navbar-github-icon" aria-hidden="true"></i><span class="sr-only">GitHub</span>',
            'aria-label': 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Alpaca',
            items: [
              {
                label: 'Alpaca Docs',
                href: 'https://alpaca.markets/docs/',
              },
              {
                label: 'Alpaca Markets',
                href: 'https://alpaca.markets/',
              },
            ],
          },
          {
            title: 'Community',
            items: [
              footerProjectLink('Slack', 'https://alpaca.markets/slack', 'fa-brands fa-slack'),
              footerProjectLink('Forum', 'https://forum.alpaca.markets/', 'fa-brands fa-discourse'),
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Alpaca. Built with Docusaurus.`,
      },
      prism: {
        theme: lightCodeTheme,
        darkTheme: darkCodeTheme,
        additionalLanguages: ['java', 'bash', 'properties'],
      },
      colorMode: {
        defaultMode: 'light',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },
    }),
};

module.exports = config;
