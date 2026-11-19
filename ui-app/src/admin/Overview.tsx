import { SERVICES } from '../api/serverInfo';
import { ServerPanel } from '../components/ServerPanel';
import { TrafficDistribution } from '../components/TrafficDistribution';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { KpiCards } from './KpiCards';
import { LowStockPanel } from './LowStockPanel';
import { ServiceStatus } from './ServiceStatus';

/**
 * The first page of the admin area: the main numbers, whether each service answers, what is low on stock,
 * then the older panels that show which server answered and how traffic is split between servers.
 */
export function Overview() {
  useDocumentTitle('Admin overview');
  return (
    <>
      <header className="page-head">
        <h1>Overview</h1>
        <p className="muted">The main numbers, how the services are doing, which server answered, and how traffic is split.</p>
      </header>

      <KpiCards />

      <div className="overview-split">
        <ServiceStatus />
        <LowStockPanel />
      </div>

      <div className="panels">
        {SERVICES.map((service) => (
          <ServerPanel key={service.key} service={service} />
        ))}
      </div>

      <TrafficDistribution />
    </>
  );
}
