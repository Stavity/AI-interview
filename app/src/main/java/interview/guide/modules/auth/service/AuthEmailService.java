package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.config.AuthProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class AuthEmailService {
    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    public AuthEmailService(JavaMailSender mailSender, AuthProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void ensureAvailable() {
        if (!properties.isMailEnabled()) {
            throw new BusinessException(503, "账号邮件服务尚未配置，请联系管理员");
        }
    }

    public void sendVerification(String email, String displayName, String token) {
        ensureAvailable();
        String link = actionLink("/verify-email", token);
        send(email, "完成邮箱验证", "你好，" + displayName + "：\n\n请打开以下链接并确认，以激活你的账号：\n" +
                link + "\n\n该链接 24 小时内有效，只能使用一次。若你没有发起注册，请忽略此邮件。");
    }

    public void sendPasswordReset(String email, String displayName, String token) {
        ensureAvailable();
        String link = actionLink("/reset-password", token);
        send(email, "重置登录密码", "你好，" + displayName + "：\n\n请打开以下链接设置新密码：\n" +
                link + "\n\n该链接 30 分钟内有效，只能使用一次。若你没有申请重置密码，请忽略此邮件。");
    }

    private String actionLink(String path, String token) {
        String baseUrl = properties.getFrontendBaseUrl().replaceAll("/+$", "");
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(path)
                .fragment("token=" + token)
                .build()
                .toUriString();
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMailFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
