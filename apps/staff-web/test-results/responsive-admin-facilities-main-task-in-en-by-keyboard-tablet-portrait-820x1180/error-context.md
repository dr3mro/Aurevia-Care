# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: responsive-admin.spec.ts >> facilities: main task in en by keyboard
- Location: e2e/responsive-admin.spec.ts:1099:7

# Error details

```
Error: expect(locator).toBeFocused() failed

Locator: getByRole('form', { name: 'Create facility' }).locator('#facility-name')
Expected: focused
Error: Target page, context or browser has been closed

Call log:
  - Expect "toBeFocused" getByRole('form', { name: 'Create facility' }).locator('#facility-name') with timeout 5000ms
  - waiting for getByRole('form', { name: 'Create facility' }).locator('#facility-name')
  - Target page, context or browser has been closed

```

# Test source

```ts
  512 |     await page.keyboard.press('Enter');
  513 |   }
  514 |   const scope = compact ? page.locator('#app-user-menu') : page;
  515 |   const language = scope.getByRole('button', { name: 'Language', exact: true });
  516 |   await language.focus();
  517 |   await page.keyboard.press('Enter');
  518 |   if (compact) {
  519 |     // The open user menu overlays content in RTL; close it through the real
  520 |     // control before any pointer assertion measures the screen.
  521 |     await menu.focus();
  522 |     await page.keyboard.press('Enter');
  523 |   }
  524 | }
  525 | 
  526 | /** Navigate to one screen and prove the real localized screen mounted. */
  527 | async function navigateScreen(page: Page, screen: Screen, words: Words): Promise<void> {
  528 |   await page.goto(screen.path);
  529 |   await expect(page.locator('.app-shell[data-breakpoint]')).toBeVisible();
  530 |   const main = page.locator('main#main');
  531 |   const heading = () =>
  532 |     main.getByRole('heading', { name: words.text(screen.headingKey), exact: true }).first();
  533 |   // The provider boots in English; the locale is changed through the visible
  534 |   // control only after the real English screen has rendered.
  535 |   await expect(
  536 |     main
  537 |       .getByRole('heading', { name: adminText('en', screen.headingKey), exact: true })
  538 |       .first(),
  539 |   ).toBeVisible();
  540 |   if (words.locale === 'ar') {
  541 |     await switchToArabic(page);
  542 |   }
  543 |   await expect(page.locator('html')).toHaveAttribute('lang', words.locale);
  544 |   await expect(page.locator('html')).toHaveAttribute('dir', words.direction);
  545 |   await expect(heading()).toBeVisible();
  546 | }
  547 | 
  548 | /** Reset all routes and open one screen with a fresh deterministic boundary. */
  549 | async function openScreen(
  550 |   page: Page,
  551 |   baseURL: string | undefined,
  552 |   screen: Screen,
  553 |   words: Words,
  554 | ): Promise<Store> {
  555 |   await page.unrouteAll();
  556 |   const store = createStore();
  557 |   await installOnPage(page, baseURL, store);
  558 |   await navigateScreen(page, screen, words);
  559 |   return store;
  560 | }
  561 | 
  562 | /** Everything one screen task needs, passed as a single bounded context. */
  563 | interface TaskContext {
  564 |   readonly page: Page;
  565 |   readonly store: Store;
  566 |   readonly words: Words;
  567 |   readonly screen: Screen;
  568 |   readonly mode: Mode;
  569 | }
  570 | 
  571 | /** True when the current Playwright project emulates a coarse pointer. */
  572 | function hasTouchPointer(): boolean {
  573 |   return test.info().project.use.hasTouch === true;
  574 | }
  575 | 
  576 | /** Activate a native control by the input method under test. */
  577 | async function activate(ctx: TaskContext, control: Locator): Promise<void> {
  578 |   if (ctx.mode === 'pointer') {
  579 |     if (hasTouchPointer()) {
  580 |       await control.tap();
  581 |     } else {
  582 |       await control.click();
  583 |     }
  584 |     return;
  585 |   }
  586 |   await control.focus();
  587 |   await expect(control).toBeFocused();
  588 |   await ctx.page.keyboard.press('Enter');
  589 | }
  590 | 
  591 | /** Enter text through the chosen modality and prove it landed. */
  592 | async function enter(ctx: TaskContext, field: Locator, value: string): Promise<void> {
  593 |   if (ctx.mode === 'pointer' && hasTouchPointer()) {
  594 |     await field.tap();
  595 |   } else {
  596 |     await field.focus();
  597 |   }
  598 |   await field.fill(value);
  599 |   await expect(field).toHaveValue(value);
  600 | }
  601 | 
  602 | /** Submit a form, proving the keyboard tab order before Enter on that path. */
  603 | async function submitForm(ctx: TaskContext, form: Locator, fields: readonly Locator[]): Promise<void> {
  604 |   const submit = form.locator('button[type="submit"]');
  605 |   if (ctx.mode === 'keyboard') {
  606 |     const first = fields[0];
  607 |     if (first === undefined) {
  608 |       throw new Error('a keyboard submit needs at least one field');
  609 |     }
  610 |     await first.focus();
  611 |     for (const field of fields) {
> 612 |       await expect(field).toBeFocused();
      |                           ^ Error: expect(locator).toBeFocused() failed
  613 |       await ctx.page.keyboard.press('Tab');
  614 |     }
  615 |     await expect(submit).toBeFocused();
  616 |     await ctx.page.keyboard.press('Enter');
  617 |     return;
  618 |   }
  619 |   await activate(ctx, submit);
  620 | }
  621 | 
  622 | /** Structure returned by the in-browser overflow measurement. */
  623 | interface OverflowMeasurement {
  624 |   readonly documentScrollWidth: number;
  625 |   readonly documentClientWidth: number;
  626 |   readonly mainScrollWidth: number;
  627 |   readonly mainClientWidth: number;
  628 |   readonly offenders: readonly string[];
  629 |   readonly scrollRegions: number;
  630 | }
  631 | 
  632 | /**
  633 |  * Measure overflow inside the screen. A labelled, keyboard-focusable scroller
  634 |  * is the intended way to keep a wide clinical table usable at 320 px, so its
  635 |  * subtree is exempt from the painted-edge scan; the document-level check for
  636 |  * Arabic stays in a dedicated fixme because of the index.html skip-link defect.
  637 |  */
  638 | async function measureOverflow(page: Page): Promise<OverflowMeasurement> {
  639 |   await page.evaluate(() => document.fonts.ready);
  640 |   return page.evaluate(() => {
  641 |     const main = document.querySelector('main#main');
  642 |     const empty: OverflowMeasurement = {
  643 |       documentScrollWidth: document.documentElement.scrollWidth,
  644 |       documentClientWidth: document.documentElement.clientWidth,
  645 |       mainScrollWidth: -1,
  646 |       mainClientWidth: 0,
  647 |       offenders: [],
  648 |       scrollRegions: 0,
  649 |     };
  650 |     if (!(main instanceof HTMLElement)) {
  651 |       return empty;
  652 |     }
  653 |     const elements = Array.from(main.querySelectorAll('*')).filter(
  654 |       (element): element is HTMLElement => element instanceof HTMLElement,
  655 |     );
  656 |     const isScrollRegion = (element: HTMLElement): boolean => {
  657 |       const overflow = getComputedStyle(element).overflowX;
  658 |       return (
  659 |         element.getAttribute('role') === 'region' &&
  660 |         element.tabIndex === 0 &&
  661 |         (element.getAttribute('aria-label') ?? '').trim().length > 0 &&
  662 |         (overflow === 'auto' || overflow === 'scroll')
  663 |       );
  664 |     };
  665 |     const regions = elements.filter(isScrollRegion);
  666 |     const offenders: string[] = [];
  667 |     const clientWidth = empty.documentClientWidth;
  668 |     // Measure edges relative to the screen, not the current viewport scroll:
  669 |     // the known RTL skip-link defect shifts the whole document sideways and
  670 |     // would otherwise make every element look off-screen.
  671 |     const mainRect = main.getBoundingClientRect();
  672 |     for (const element of elements) {
  673 |       if (regions.includes(element) || regions.some((region) => region.contains(element))) {
  674 |         continue;
  675 |       }
  676 |       const rect = element.getBoundingClientRect();
  677 |       const visible =
  678 |         rect.width > 0 && rect.height > 0 && getComputedStyle(element).visibility === 'visible';
  679 |       if (!visible) {
  680 |         continue;
  681 |       }
  682 |       const relativeLeft = rect.left - mainRect.left;
  683 |       const relativeRight = rect.right - mainRect.left;
  684 |       if (
  685 |         relativeRight > mainRect.width + 1 ||
  686 |         relativeLeft < -1 ||
  687 |         element.scrollWidth > element.clientWidth + 1
  688 |       ) {
  689 |         const id = element.id === '' ? '' : `#${element.id}`;
  690 |         offenders.push(`${element.tagName.toLowerCase()}${id}.${element.className}`);
  691 |       }
  692 |     }
  693 |     return {
  694 |       documentScrollWidth: empty.documentScrollWidth,
  695 |       documentClientWidth: clientWidth,
  696 |       mainScrollWidth: main.scrollWidth,
  697 |       mainClientWidth: main.clientWidth,
  698 |       offenders,
  699 |       scrollRegions: regions.length,
  700 |     };
  701 |   });
  702 | }
  703 | 
  704 | /** Assert the screen itself has no overflow and no unlabelled offender. */
  705 | async function expectNoHorizontalScroll(ctx: TaskContext): Promise<void> {
  706 |   const measured = await measureOverflow(ctx.page);
  707 |   const context = `${ctx.screen.id} | ${test.info().project.name} | ${ctx.words.locale} | ${ctx.mode}`;
  708 |   expect(measured.mainScrollWidth, `main overflow: ${context}`).toBeLessThanOrEqual(
  709 |     measured.mainClientWidth,
  710 |   );
  711 |   expect(measured.offenders, `screen overflow: ${context}`).toEqual([]);
  712 |   if (ctx.words.locale === 'en') {
```