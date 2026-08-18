import { expect, test } from '@playwright/test';

test('学习计划可创建任务并完成', async ({ page }) => {
  let plans: unknown[] = [];
  let tasks: unknown[] = [];
  await page.route('**/api/learning/plans', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: plans }) });
    plans = [{ id: 8, title: 'Java 基础巩固', goal: '', startDate: '2026-08-18', endDate: '2026-08-18', status: 'DRAFT' }];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: plans[0] }) });
  });
  await page.route('**/api/learning/plans/8/tasks', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: tasks }) });
    tasks = [{ id: 20, planId: 8, title: '复习集合框架', dueDate: '2026-08-18', estimatedMinutes: 30, status: 'PENDING' }];
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: tasks[0] }) });
  });
  await page.route('**/api/learning/plans/8/activate', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: { id: 8, title: 'Java 基础巩固', goal: '', startDate: '2026-08-18', endDate: '2026-08-18', status: 'ACTIVE' } }) }));
  await page.route('**/api/learning/tasks/20/complete', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: null }) }));
  await page.route('**/api/learning/sources', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: [] }) }));
  await page.route('**/api/learning/progress', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: [] }) }));
  await page.route('**/api/learning/path-recommendation', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: [] }) }));

  await page.goto('/learning');
  await page.getByPlaceholder('新学习计划').fill('Java 基础巩固');
  await page.getByRole('button', { name: '创建学习计划' }).click();
  await expect(page.getByText('Java 基础巩固').first()).toBeVisible();
  await page.getByRole('button', { name: '确认并启用计划' }).click();
  await page.getByPlaceholder('添加学习任务').fill('复习集合框架');
  await page.getByRole('button', { name: '添加' }).click();
  await expect(page.getByText('复习集合框架')).toBeVisible();
  await page.getByRole('button', { name: '完成任务' }).click();
  await expect(page.getByText('复习集合框架')).toHaveClass(/line-through/);
});
