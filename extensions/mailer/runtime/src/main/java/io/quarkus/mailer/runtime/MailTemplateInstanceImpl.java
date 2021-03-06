package io.quarkus.mailer.runtime;

import static io.quarkus.qute.api.VariantTemplate.SELECTED_VARIANT;
import static io.quarkus.qute.api.VariantTemplate.VARIANTS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MailTemplate;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.Variant;
import io.quarkus.qute.api.VariantTemplate;
import io.smallrye.mutiny.Uni;

class MailTemplateInstanceImpl implements MailTemplate.MailTemplateInstance {

    private final MutinyMailerImpl mailer;
    private final TemplateInstance templateInstance;
    private final Map<String, Object> data;
    private Mail mail;

    MailTemplateInstanceImpl(MutinyMailerImpl mailer, TemplateInstance templateInstance) {
        this.mailer = mailer;
        this.templateInstance = templateInstance;
        this.data = new HashMap<>();
        this.mail = new Mail();
    }

    @Override
    public MailTemplateInstance mail(Mail mail) {
        this.mail = mail;
        return this;
    }

    @Override
    public MailTemplateInstance to(String... to) {
        this.mail.addTo(to);
        return this;
    }

    @Override
    public MailTemplateInstance cc(String... cc) {
        this.mail.addCc(cc);
        return this;
    }

    @Override
    public MailTemplateInstance bcc(String... bcc) {
        this.mail.addBcc(bcc);
        return this;
    }

    @Override
    public MailTemplateInstance subject(String subject) {
        this.mail.setSubject(subject);
        return this;
    }

    @Override
    public MailTemplateInstance from(String from) {
        this.mail.setFrom(from);
        return this;
    }

    @Override
    public MailTemplateInstance replyTo(String replyTo) {
        this.mail.setReplyTo(replyTo);
        return this;
    }

    @Override
    public MailTemplateInstance bounceAddress(String bounceAddress) {
        this.mail.setBounceAddress(bounceAddress);
        return this;
    }

    @Override
    public MailTemplateInstance data(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    @Override
    public CompletionStage<Void> send() {
        if (templateInstance.getAttribute(VariantTemplate.VARIANTS) != null) {

            List<Result> results = new ArrayList<>();

            @SuppressWarnings("unchecked")
            List<Variant> variants = (List<Variant>) templateInstance.getAttribute(VARIANTS);
            for (Variant variant : variants) {
                if (variant.mediaType.equals(Variant.TEXT_HTML) || variant.mediaType.equals(Variant.TEXT_PLAIN)) {
                    results.add(new Result(variant,
                            Uni.createFrom().completionStage(
                                    () -> templateInstance.setAttribute(SELECTED_VARIANT, variant).data(data).renderAsync())));
                }
            }

            if (results.isEmpty()) {
                throw new IllegalStateException("No suitable template variant found");
            }

            List<Uni<String>> unis = results.stream().map(Result::getValue).collect(Collectors.toList());
            return Uni.combine().all().unis(unis)
                    .combinedWith(combine(results))
                    .onItem().produceUni(m -> mailer.send((Mail) m))
                    .subscribeAsCompletionStage();
        } else {
            throw new IllegalStateException("No template variant found");
        }
    }

    private Function<List<String>, Mail> combine(List<Result> results) {
        return ignored -> {
            for (Result res : results) {
                // We can safely access the content here: 1. it has been resolved, 2. it's cached.
                String content = res.value.await().indefinitely();
                if (res.variant.mediaType.equals(Variant.TEXT_HTML)) {
                    mail.setHtml(content);
                } else if (res.variant.mediaType.equals(Variant.TEXT_PLAIN)) {
                    mail.setText(content);
                }
            }
            return mail;
        };
    }

    static class Result {

        final Variant variant;
        final Uni<String> value;

        public Result(Variant variant, Uni<String> result) {
            this.variant = variant;
            this.value = result.cache();
        }

        Uni<String> getValue() {
            return value;
        }
    }

}
