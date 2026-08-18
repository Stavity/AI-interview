import { expect, test } from '@playwright/test';

test('普通 Chat 创建后刷新可恢复最近会话与消息历史', async ({ page }) => {
  let created = false;
  const session = { id: 91, title: '架构讨论', pinned: false, providerId: 'provider-a', status: 'ACTIVE', messageCount: 2, updatedAt: '2026-08-18T00:00:00' };
  await page.route('**/api/chat/sessions', async route => {
    if (route.request().method() === 'POST') created = true;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: route.request().method() === 'POST' ? session : (created ? [session] : []) }) });
  });
  await page.route('**/api/chat/sessions/91?*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: { session, messages: [
    { id: 1, role: 'user', content: '解释幂等设计', completed: true, messageOrder: 1, createdAt: '2026-08-18T00:00:00' },
    { id: 2, role: 'assistant', content: '使用唯一键和请求键。', completed: true, messageOrder: 2, createdAt: '2026-08-18T00:00:01' },
  ], page: 0, size: 50, totalElements: 2, totalPages: 1 } }) }));

  await page.goto('/chat');
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page.getByRole('heading', { name: '架构讨论' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: '架构讨论' })).toBeVisible();
  await expect(page.getByText('使用唯一键和请求键。')).toBeVisible();
});
