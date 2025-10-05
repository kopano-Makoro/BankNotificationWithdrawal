import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
@RequestMapping("/bank")
public class BankAccountController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    private SnsClient snsClient;

    public BankAccountController() {
        this.snsClient = SnsClient.builder()
            .region(Region.US_EAST_1)
            .build();
    }

    @PostMapping("/withdraw")
    @Transactional
    public String withdraw(@RequestParam("accountId") Long accountId, @RequestParam("amount") BigDecimal amount) {
        try {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                publishWithdrawalEvent(amount, accountId, "INVALID_AMOUNT");
                return "Invalid withdrawal amount";
            }

            String sql = "SELECT balance FROM accounts WHERE id = ?";
            BigDecimal currentBalance = jdbcTemplate.queryForObject(sql, new Object[]{accountId}, BigDecimal.class);

            if (currentBalance != null && currentBalance.compareTo(amount) >= 0) {
                sql = "UPDATE accounts SET balance = balance - ? WHERE id = ?";
                int rowsAffected = jdbcTemplate.update(sql, amount, accountId);

                if (rowsAffected > 0) {
                    publishWithdrawalEvent(amount, accountId, "SUCCESSFUL");
                    return "Withdrawal successful";
                } else {
                    publishWithdrawalEvent(amount, accountId, "FAILED");
                    return "Withdrawal failed";
                }
            } else {
                publishWithdrawalEvent(amount, accountId, "INSUFFICIENT_FUNDS");
                return "Insufficient funds for withdrawal";
            }
        } catch (Exception e) {
            publishWithdrawalEvent(amount, accountId, "ERROR");
            return "Error processing withdrawal: " + e.getMessage();
        }
    }

    private void publishWithdrawalEvent(BigDecimal amount, Long accountId, String status) {
        try {
            WithdrawalEvent event = new WithdrawalEvent(amount, accountId, status);
            String eventJson = event.toJson();
            String snsTopicArn = "arn:aws:sns:us-east-1:YOUR_ACCOUNT_ID:YOUR_TOPIC_NAME";
            PublishRequest publishRequest = PublishRequest.builder()
                .message(eventJson)
                .topicArn(snsTopicArn)
                .build();
            snsClient.publish(publishRequest);
        } catch (Exception e) {
            System.err.println("Failed to publish SNS event: " + e.getMessage());
        }
    }
}
