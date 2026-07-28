# OpenAPI 请求签名协议

数据服务开放接口统一通过 `api-gateway-service` 暴露。调用方从数据服务平台申请
`AppKey` 和 `AppSecret`，并由管理员授权可访问的 API。

## 请求头

| 请求头 | 必填 | 说明 |
| --- | --- | --- |
| `X-App-Key` | 是 | 调用应用标识 |
| `X-Timestamp` | 是 | Unix 毫秒时间戳，默认允许前后 5 分钟偏差 |
| `X-Nonce` | 是 | 16-120 位随机字符串，同一应用不可重复使用 |
| `X-Signature` | 是 | HMAC-SHA256 十六进制小写签名 |

## 规范签名串

按以下顺序拼接，每一项之间使用一个换行符 `\n`，最后一项后不加换行：

```text
HTTP_METHOD
REQUEST_PATH
RAW_QUERY_STRING
X_TIMESTAMP
X_NONCE
SHA256_HEX_OF_RAW_BODY
```

示例：

```text
GET
/openapi/governance/call-logs
page=1&pageSize=20
1785141000000
8f49e2ef09d54cb798604f001880fe38
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

签名计算：

```text
X-Signature = lowercase_hex(
  HMAC_SHA256(AppSecret, canonical_request)
)
```

注意：

- 查询参数必须按实际发送的原始顺序参与签名，不要重新排序后再发送。
- GET 无请求体时，Body SHA-256 是空字节数组的 SHA-256。
- POST 必须对实际发送的原始请求体字节计算 SHA-256。
- Nonce 只能使用一次；重复请求会返回 `401`。
- 超出应用 QPS 会返回 `429`。
- 未授权 API 会返回 `403`。
- `AppSecret` 只在创建或轮换时展示一次，不应写入源码或前端包。

生产环境必须通过 `DATA_SERVICE_MASTER_KEY` 注入至少 32 字符的独立主密钥，
并由 K8s Secret、Vault 或云 KMS 管理，禁止使用仓库中的本地开发默认值。
