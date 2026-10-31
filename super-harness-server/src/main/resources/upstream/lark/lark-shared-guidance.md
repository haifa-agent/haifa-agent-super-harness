<!-- haifa-lark-cli-auth-guidance-v1 -->

## Haifa Super Harness 授权入口

在 Haifa Super Harness 中，如果 `lark-cli auth status` 或业务命令提示未配置、未登录、token 过期或缺少用户授权：

1. 不要要求用户在终端执行 `lark-cli config init`、`lark-cli auth login` 或 `lark-cli auth login --device-code`。
2. 回复用户这个可点击链接：[打开飞书授权设置](/workspace/capabilities?tab=plugins&plugin=lark)。
3. 告诉用户在 **Capability Center → Plugins → Lark / Feishu** 点击“连接飞书”，在浏览器里完成授权后再回来继续当前任务。
4. 如果错误中包含缺失的 `scope`、`permission_violations` 或建议的 `--domain`，告诉用户在该设置页选择对应权限域（例如日历选择 Calendar），或把具体 scope 填入“Exact OAuth scope / 具体 OAuth scope”后重新授权。

只有在用户明确说明已经完成授权后，才继续调用具体的 `lark-cli` 业务命令。
