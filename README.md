# Apresenter Android

Aplicativos Android oficiais do ecossistema **Apresenter** (compatíveis com o servidor local do **Apresenter Desktop** na porta `8765`), reunidos em um único repositório que gera **dois APKs distintos por release**:

- **`Apresenter-Celular.apk` (`:app-mobile` / `org.apresenter.mobile`)**: controle remoto em toque para apresentação, Roteiro, Bíblia, músicas, Palco/Cifras e moderação P&R.
- **`Apresenter-TV.apk` (`:app-tv` / `org.apresenter.tv`)**: **apenas receptor** de Sinalização (`Platform.ANDROID_TV`) para Smart TVs Android/Google TV e TV Box — tela cheia, D-pad, sem câmera nem permissões de toque, pareamento por código e cache offline por SHA-256.

---

## Estrutura do Projeto

- **`:core`** — biblioteca compartilhada: descoberta na rede (`NsdManager` para `_churchpresenter._tcp.`), cliente REST/WebSocket Companion (`/ws`, `/api/*`), protocolo e motor de decisão Signage (`SignageModels`, `decide()`), cliente `/api/signage/ws`, listener UDP Nudge (`47640`) e cache offline com verificação SHA-256.
- **`:app-mobile`** — aplicativo para celulares e tablets (Material 3, modo claro/escuro).
- **`:app-tv`** — aplicativo receptor para Android TV / Google TV (`LEANBACK_LAUNCHER`, `touchscreen required="false"`).

---

## Compilação e Releases no GitHub Actions

Os builds e testes rodam automaticamente no workflow [`.github/workflows/build.yml`](.github/workflows/build.yml):

- A cada `push` na `main` ou PR: executa `:core:testDebugUnitTest`, `:app-mobile:assembleDebug` e `:app-tv:assembleDebug`.
- Ao enviar uma tag `v*` (ex.: `git tag -a v0.1.0 -m "v0.1.0" && git push origin v0.1.0`): gera os dois instaladores `Apresenter-Celular-<tag>.apk` e `Apresenter-TV-<tag>.apk` e publica ambos na mesma **GitHub Release**.

### Assinatura de Produção (Opcional via Secrets)

Por padrão, enquanto nenhuma keystore de produção for configurada nos Secrets do repositório, o build de release usa assinatura debug para permitir instalação imediata em testes. Quando quiser publicar com sua chave definitiva, cadastre nos Secrets do GitHub:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

---

## Licença

Distribuído sob **GNU GPL v3** (ver [LICENSE.txt](LICENSE.txt)).
