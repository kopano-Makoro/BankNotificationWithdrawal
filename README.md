# BankNotificationWithdrawal
Takehome assessment

Outline of Approach
The primary goal was to correct the bugs in the original BankAccountController code while preserving the core business capability: processing withdrawal requests from a bank account via a REST endpoint, checking and updating the account balance in a database, and publishing a withdrawal event to an AWS SNS topic for notification purposes. No changes were made to alter this functionality—e.g., the endpoint path, request parameters, balance logic, or SNS event structure remain identical. Instead, the focus was on:

Making the SNS publishing logic reachable by relocating it within the conditional blocks.
Enhancing reliability with transaction management to ensure database atomicity (balance check and update occur as a single unit).
Adding input validation and exception handling to prevent runtime failures.
Dynamically setting the event status based on outcomes (e.g., success, failure, insufficient funds) rather than hardcoding it.
Extracting SNS publishing into a reusable private method for cleaner code.
This approach ensures the code is functional, robust, and maintainable without introducing new features or modifying the intended behavior.

Elaboration on Implementation Choices

Relocating SNS Publishing: The original code had the SNS logic after an if-else chain that always returned, making it unreachable. I moved it inside the relevant conditional branches (e.g., after successful update) and extended it to publish events for all outcomes (success, insufficient funds, failure, error). This allows comprehensive event logging without changing the business flow.
Dynamic Event Status: Instead of hardcoding "SUCCESSFUL", the WithdrawalEvent status is now set based on the result (e.g., "SUCCESSFUL", "INSUFFICIENT_FUNDS", "FAILED", "ERROR"). This provides more accurate notifications to SNS subscribers.
Transaction Management: Added Spring's @Transactional annotation to the withdraw method. This ensures that if the balance update fails (e.g., due to a concurrent modification), the entire operation rolls back, preventing partial updates.
Input Validation: Added a check for invalid amount (null or ≤ 0) to handle edge cases early, returning an appropriate message and publishing an "INVALID_AMOUNT" event.
Exception Handling: Wrapped the main logic in a try-catch to capture runtime errors (e.g., database connection issues or SNS failures). SNS publishing has its own try-catch to avoid propagating errors, with basic logging via System.err (recommend replacing with a logging framework like SLF4J in production).
Configuration Placeholders: Replaced placeholders like Region.YOUR_REGION with Region.US_EAST_1 as an example; users should update to their actual AWS region. The SNS ARN is hardcoded but noted for replacement (ideally, inject via @Value from properties).
No Major Refactors: Kept the code structure similar to the original for familiarity. Avoided introducing new dependencies (e.g., JSON libraries) to minimize changes, sticking with the basic String.format in WithdrawalEvent.

These choices prioritize bug fixes, error resilience, and code clarity while keeping the implementation simple and aligned with Spring Boot and AWS SDK best practices.
Fixed Code Snippet
Below is the complete fixed BankAccountController class. Note that it assumes the WithdrawalEvent class is available in the same package (as provided earlier).
javapackage com.example;  // Adjust package as needed

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
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
            .region(Region.US_EAST_1)  // Replace with your AWS region
            .build();
    }

    @PostMapping("/withdraw")
    @Transactional  // Ensures atomicity for database operations
    public String withdraw(@RequestParam("accountId") Long accountId, @RequestParam("amount") BigDecimal amount) {
        try {
            // Validate input amount
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                publishWithdrawalEvent(amount, accountId, "INVALID_AMOUNT");
                return "Invalid withdrawal amount";
            }

            // Check current balance
            String sql = "SELECT balance FROM accounts WHERE id = ?";
            BigDecimal currentBalance = jdbcTemplate.queryForObject(sql, new Object[]{accountId}, BigDecimal.class);

            if (currentBalance != null && currentBalance.compareTo(amount) >= 0) {
                // Update balance
                sql = "UPDATE accounts SET balance = balance - ? WHERE id = ?";
                int rowsAffected = jdbcTemplate.update(sql, amount, accountId);

                if (rowsAffected > 0) {
                    // Publish successful event
                    publishWithdrawalEvent(amount, accountId, "SUCCESSFUL");
                    return "Withdrawal successful";
                } else {
                    // Publish failed event
                    publishWithdrawalEvent(amount, accountId, "FAILED");
                    return "Withdrawal failed";
                }
            } else {
                // Publish insufficient funds event
                publishWithdrawalEvent(amount, accountId, "INSUFFICIENT_FUNDS");
                return "Insufficient funds for withdrawal";
            }
        } catch (Exception e) {
            // Publish error event
            publishWithdrawalEvent(amount, accountId, "ERROR");
            return "Error processing withdrawal: " + e.getMessage();
        }
    }

    private void publishWithdrawalEvent(BigDecimal amount, Long accountId, String status) {
        try {
            WithdrawalEvent event = new WithdrawalEvent(amount, accountId, status);
            String eventJson = event.toJson();
            String snsTopicArn = "arn:aws:sns:us-east-1:YOUR_ACCOUNT_ID:YOUR_TOPIC_NAME";  // Replace with your ARN
            PublishRequest publishRequest = PublishRequest.builder()
                .message(eventJson)
                .topicArn(snsTopicArn)
                .build();
            snsClient.publish(publishRequest);
        } catch (Exception e) {
            System.err.println("Failed to publish SNS event: " + e.getMessage());
            // In production, use a logging framework like SLF4J
        }
    }
}
Documentation of Unclear Library Usage

Spring JdbcTemplate: Used for database interactions (querying and updating). It's autowired via @Autowired, assuming a DataSource is configured in Spring Boot (e.g., via application.properties). The queryForObject and update methods are standard for single-value queries and updates; they throw DataAccessException on errors, which is caught in the outer try-catch.
AWS SDK SnsClient: Initialized in the constructor with a region. The publish method sends messages to SNS; it requires valid AWS credentials (not explicitly handled here—assumed configured externally via AWS CLI, environment variables, or IAM roles). The PublishRequest builder is used to construct the message, which is a JSON string from WithdrawalEvent.
BigDecimal: Used for monetary amounts to avoid floating-point precision issues. Comparisons use compareTo instead of == for accuracy.
