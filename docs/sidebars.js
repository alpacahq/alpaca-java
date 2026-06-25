/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  wikiSidebar: [
    'intro',
    'getting-started',
    {
      type: 'category',
      label: 'SDK Areas',
      collapsed: false,
      items: [
        'sdk/trading',
        'sdk/market-data',
        'sdk/broker',
        {
          type: 'doc',
          id: 'sdk/streaming',
          label: 'Streaming & Events',
        },
      ],
    },
  ],
};

module.exports = sidebars;
