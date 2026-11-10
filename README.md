# Bloodlight

Escurece a tela além do brilho mínimo do Android. O vampiro é o controle: arrastar para a esquerda escurece, para a direita clareia.

## Como gerar o APK
1. Abrir esta pasta no Android Studio (https://developer.android.com/studio) e esperar o sync do Gradle.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. Passar `app/build/outputs/apk/debug/app-debug.apk` para o celular e instalar.

## Instalar sem Android Studio
O GitHub compila a cada envio para a `main` e publica o APK em:
https://github.com/Samuelborzeto/bloodlight/releases/download/latest/Bloodlight.apk

## Primeiro uso
- O botão "Permitir escurecer a tela" abre a permissão "Exibir sobre outros apps". Ativar para o Bloodlight.
- Para escurecer quase até o preto: botão "Escurecer mais" > ativar "Bloodlight (escurecimento forte)" em Acessibilidade. Sem isso, o limite é 79% de escurecimento.
- Se o Android disser que é uma configuração restrita: Informações do app > ⋮ > Permitir configurações restritas.
- Tocar fora da barra fecha o app (o escurecimento continua).
- Para ligar/desligar rápido: adicionar o bloco "Bloodlight" no painel de configurações rápidas.
- A notificação fixa tem o botão "Desligar".

## Arte
`app/src/main/res/drawable-nodpi/`: `head_0` (exposto), `head_1` (recuperando), `head_2` (protegido), `head_0_blink` e `head_1_blink` (piscando), `cap_left`, `cap_right`.
Para trocar os rostos por arte nova, substituir os 3 `head_*.png` (370x330, fundo transparente, mesmo enquadramento).
