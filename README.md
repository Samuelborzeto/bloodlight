# Bloodlight

Escurece a tela além do brilho mínimo do Android. O vampiro é o controle: arrastar para a esquerda escurece, para a direita clareia.

## Como gerar o APK
1. Abrir esta pasta no Android Studio (https://developer.android.com/studio) e esperar o sync do Gradle.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. Passar `app/build/outputs/apk/debug/app-debug.apk` para o celular e instalar.

## Primeiro uso
- O botão "Permitir escurecer a tela" abre a permissão "Exibir sobre outros apps". Ativar para o Bloodlight.
- Para ligar/desligar rápido: adicionar o bloco "Bloodlight" no painel de configurações rápidas.
- A notificação fixa tem o botão "Desligar".

## Arte
`app/src/main/res/drawable-nodpi/`: `head_0` (exposto), `head_1` (recuperando), `head_2` (protegido), `cap_left`, `cap_right`.
Para trocar os rostos por arte nova, substituir os 3 `head_*.png` (370x330, fundo transparente, mesmo enquadramento).
