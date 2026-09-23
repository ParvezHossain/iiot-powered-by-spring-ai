import {Component} from '@angular/core';
import {RouterLink} from '@angular/router';

@Component({
    selector: 'app-not-found',
    imports: [RouterLink],
    template: `
        <section class="panel p-8 text-slate-200 sm:p-12">
            <h1 class="text-3xl font-bold">Page not found</h1>
            <a routerLink="/" class="mt-4 inline-block text-indigo-300 underline">Return home</a>
        </section>
    `,
})
export class NotFoundComponent {
}
