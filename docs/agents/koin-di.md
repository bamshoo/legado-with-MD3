# 依赖注入（Koin）

`App.onCreate()` 中加载两个模块：

```kotlin
startKoin {
    modules(appDatabaseModule, appModule)
}
```

## `di/appDatabaseModule.kt`

- Singleton `AppDatabase`
- 全部 22 个 DAO 的 factory 绑定

## `di/appModule.kt`

- Singleton：仓库、用例、Gateway、Coil `ImageLoader`
- `viewModelOf` / `viewModel { }`：全部 ViewModel，部分带参数

## Gateway 绑定约定

Gateway 显式绑定到其仓库实现，不用 `singleOf`：

```kotlin
single<LocalBookGateway> { LocalBookRepository(get()) }
```
