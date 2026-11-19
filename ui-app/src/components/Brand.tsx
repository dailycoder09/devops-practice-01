import { Link } from 'react-router-dom';
import { Icon } from './Icon';

/** The shop's name: a small bag mark and the wordmark, in white for the blue header and the dark footer. It links to the home page. */
export function Brand() {
  return (
    <Link className="brand" to="/" aria-label="MicroShop">
      <span className="brand-mark" aria-hidden="true">
        <Icon name="bag" size={20} />
      </span>
      <span className="brand-word">
        Micro<span className="brand-shop">Shop</span>
      </span>
    </Link>
  );
}
