import {Routes} from '@angular/router';
import {adminGuard} from './core/admin.guard';

export const routes: Routes = [
    {path: '', pathMatch: 'full', loadComponent: () => import('./home.component').then(m => m.HomeComponent)},
    {
        path: 'account',
        canActivate: [adminGuard],
        loadComponent: () => import('./features/account/account.component').then(m => m.AccountComponent)
    },
    {path: 'users', loadComponent: () => import('./features/admin/users.component').then(m => m.UsersComponent)},
    {
        path: 'documents',
        loadComponent: () => import('./features/documents/documents.component').then(m => m.DocumentsComponent)
    },
    {path: 'mcp-tools', loadComponent: () => import('./features/mcp/mcp.component').then(m => m.McpComponent)},
    {
        path: 'machine-status',
        loadComponent: () => import('./features/telemetry/machine-status.component').then(m => m.MachineStatusComponent)
    },
    {path: '**', loadComponent: () => import('./not-found.component').then(m => m.NotFoundComponent)},
];
