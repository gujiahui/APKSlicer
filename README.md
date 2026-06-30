# APKSlicer

APKSlicer 是一个基于 Spring Boot 的 APK 分包工具，用于将单个 APK 文件根据不同渠道号进行分包处理，生成带有不同渠道信息的 APK 文件，并支持自动上传到 CDN。

## 功能特性

- **APK分包**：支持通过上传文件或 CDN 链接获取 APK，根据配置的渠道号进行批量分包
- **层级后缀**：支持配置层级后缀（如 _01, _02），选择高级别会自动包含所有低级别的分包
- **CDN上传**：分包完成后自动上传到七牛云 CDN，生成可访问的下载链接
- **APK信息检测**：支持检测 APK 的包名、版本号、签名信息（V1/V2/V3）、渠道号等
- **断点续传**：支持大文件断点上传和下载
- **实时进度**：通过 SSE 实时推送分包进度

## 技术栈

- Java 21
- Spring Boot 4.1.0
- 七牛云 SDK 7.15.0
- Walle（渠道包生成工具）
- Thymeleaf 模板引擎
- Hutool 工具库

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+

### 配置说明

在启动前，需要配置以下敏感参数：

#### 七牛云配置

```yaml
qiniu:
  accessKey: YOUR_QINIU_ACCESS_KEY
  secretKey: YOUR_QINIU_SECRET_KEY
  bucket: YOUR_QINIU_BUCKET_NAME
  domain: YOUR_QINIU_CDN_DOMAIN
```

#### CDN配置

```yaml
cdn:
  domain: YOUR_CDN_DOMAIN
  path: tui
```

#### 存储配置

```yaml
storage:
  temp-dir: ./temp
  output-dir: ./output
```

#### 渠道号配置

```yaml
channels:
  defaults:
    - channel_baidu
    - channel_360
    - channel_default
    - channel_sg
    - channel_baidu2
    - channel_b
  options:
    - channel_sg2
    - channel_sms
    - channel_test
  levels:
    - "_01"
    - "_02"
    - "_03"
    - "_04"
    - "_05"
    - "_06"
```

### 启动方式

#### 开发环境

```bash
mvn spring-boot:run
```

#### 打包部署

```bash
mvn clean package
java -jar target/APKSlicer-0.0.1-SNAPSHOT.jar
```

### 访问地址

启动后访问：`http://localhost:8080`

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/channels` | GET | 获取渠道号配置 |
| `/api/settings` | GET | 获取系统设置 |
| `/api/settings` | POST | 更新系统设置 |
| `/api/slice` | POST | 提交分包任务 |
| `/api/progress` | GET | 查询任务进度 |
| `/api/progress/sse` | GET | SSE 实时进度 |
| `/api/check-channel` | POST | 检测 APK 信息 |
| `/api/download/status` | GET | 获取下载状态 |

## 项目结构

```
src/main/java/com/qdd/apkslicer/
├── api/              # API 返回结果封装
├── config/           # 配置类（七牛云、存储、渠道号）
├── controller/       # 控制器（页面、API）
├── entity/           # 实体类
├── enums/            # 枚举类
├── scheduler/        # 并发调度器
├── service/          # 服务层
│   └── impl/         # 服务实现
├── util/             # 工具类
└── ApkSlicerApplication.java
```

## 注意事项

1. **敏感信息保护**：配置文件中的七牛云密钥和 CDN 域名属于敏感信息，请勿提交到版本控制系统
2. **文件大小限制**：默认支持最大 5GB 的 APK 文件上传
3. **临时文件清理**：分包任务完成后会自动清理临时文件和输出目录
4. **日志配置**：日志文件存储在 `logs/` 目录下，可在 `logback.xml` 中调整配置

## 许可证

MIT License