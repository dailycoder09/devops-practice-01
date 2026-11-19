import { AccountBar } from './AccountBar';
import { Brand } from './Brand';
import { SearchBox } from './SearchBox';

/** The page header, on every page and sticky: the shop name, the search box and the account links. */
export function SiteHeader() {
  return (
    <header className="site-header">
      <div className="shell site-header-inner">
        <Brand />
        <SearchBox />
        <AccountBar />
      </div>
    </header>
  );
}
