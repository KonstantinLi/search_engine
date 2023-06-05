package searchengine.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import searchengine.validators.AvailablePageValidator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Constraint(validatedBy = AvailablePageValidator.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface AvailablePage {
    String message() default "Данная страница находится за пределами сайтов, указанных в конфигурационном файле";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
