import { Route } from 'react-router-dom';
import { AdminLayout } from './AdminLayout';
import { Inventory } from './Inventory';
import { AdminOrders } from './Orders';
import { Overview } from './Overview';
import { RequireAdmin } from './RequireAdmin';
import { AdminUsers } from './Users';

/** Every address under /admin. The pages are separate files, so each one can be replaced on its own. */
export const adminRoutes = (
  <Route
    path="/admin"
    element={
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    }
  >
    <Route index element={<Overview />} />
    <Route path="inventory" element={<Inventory />} />
    <Route path="orders" element={<AdminOrders />} />
    <Route path="users" element={<AdminUsers />} />
  </Route>
);
