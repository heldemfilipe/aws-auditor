package dev.heldem.aws_auditor.notificacao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl a API do Telegram (nos testes, o WireMock)
 * @param token o token do bot: SÓ por variável de ambiente (TELEGRAM_BOT_TOKEN), nunca no repositório; vazio desliga o aviso
 * @param chatId o chat que recebe o aviso (TELEGRAM_CHAT_ID)
 */
@ConfigurationProperties("auditor.telegram")
public record TelegramProperties(String baseUrl, String token, String chatId) {

    public TelegramProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.telegram.org" : baseUrl;
        token = token == null ? "" : token;
        chatId = chatId == null ? "" : chatId;
    }

    /** O formato do token de um bot do Telegram: número, dois-pontos e letras, dígitos, "_" ou "-". */
    private static final java.util.regex.Pattern FORMATO_DO_TOKEN = java.util.regex.Pattern.compile("[0-9]+:[A-Za-z0-9_-]+");

    public boolean ligado() {
        return !token.isBlank() && !chatId.isBlank();
    }

    public boolean tokenComFormatoValido() {
        return FORMATO_DO_TOKEN.matcher(token).matches();
    }
}
