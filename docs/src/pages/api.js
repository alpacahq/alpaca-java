import React from 'react';
import Layout from '@theme/Layout';
import useBaseUrl from '@docusaurus/useBaseUrl';

export default function ApiReference() {
  const javadocsUrl = useBaseUrl('/javadocs/index.html');

  return (
    <Layout title="API Reference" noFooter>
      <main className="api-frame-page">
        <iframe
          className="api-frame"
          src={javadocsUrl}
          title="Alpaca Java Client API Reference"
        />
      </main>
    </Layout>
  );
}
