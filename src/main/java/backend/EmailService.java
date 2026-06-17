package backend;

import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Utility service initializing SMTP configurations dynamically from db.properties
 * and routing billing notification transmissions to external clients.
 */
public class EmailService {

    private static final Logger LOGGER = Logger.getLogger(EmailService.class.getName());

    // Configured variables populated dynamically at runtime
    private static String smtpHost;
    private static String smtpPort;
    private static String senderEmail;
    private static String senderPassword;

    // Static initialization block runs automatically when the JVM references this class
    static {
        try (InputStream input = EmailService.class.getResourceAsStream("/db.properties")) {
            Properties appProps = new Properties();
            if (input != null) {
                appProps.load(input);
                
                // Read properties falling back to sensible defaults if applicable
                smtpHost       = appProps.getProperty("mail.smtp.host", "smtp.gmail.com");
                smtpPort       = appProps.getProperty("mail.smtp.port", "587");
                senderEmail    = appProps.getProperty("mail.sender.email");
                senderPassword = appProps.getProperty("mail.sender.password");
                
                LOGGER.info("EmailService context initialized dynamically from db.properties.");
            } else {
                LOGGER.severe("Initialization Failure: /db.properties file missing from classpath target.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Critical Exception intercepted while resolving structural configuration properties", e);
        }
    }

    /**
     * Spawns an asynchronous background thread execution line to construct 
     * and stream a multipart message receipt payload package to the destination target.
     */
    public static void sendReceiptEmail(String recipientEmail, String customerName, byte[] pdfBytes, String fileName) {
        if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
            LOGGER.warning("Skipping execution: Recipient address entry evaluated as completely blank.");
            return;
        }
        
        if (senderEmail == null || senderPassword == null) {
            LOGGER.severe("Abort transmission: Required email infrastructure credentials have not been configured.");
            return;
        }

        Properties mailSessionProps = new Properties();
        mailSessionProps.put("mail.smtp.auth", "true");
        mailSessionProps.put("mail.smtp.starttls.enable", "true");
        mailSessionProps.put("mail.smtp.host", smtpHost);
        mailSessionProps.put("mail.smtp.port", smtpPort);
        mailSessionProps.put("mail.smtp.ssl.trust", smtpHost);

        Session session = Session.getInstance(mailSessionProps, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderEmail, senderPassword);
            }
        });

        new Thread(() -> {
            try {
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(senderEmail, "BillDesk Store"));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
                message.setSubject("Your Digital Invoice / Receipt from BillDesk Store");

                // Construct text section
                MimeBodyPart textPart = new MimeBodyPart();
                String greetingName = (customerName != null && !customerName.isEmpty()) ? customerName : "Valued Customer";
                String bodyContent = "Dear " + greetingName + ",\n\n"
                                   + "Thank you for shopping with us! Please find attached a copy of your digital receipt generated for your recent purchase.\n\n"
                                   + "Best Regards,\n"
                                   + "Management Team\n"
                                   + "BillDesk Store";
                textPart.setText(bodyContent);

                // Build attachment section
                MimeBodyPart attachmentPart = new MimeBodyPart();
                ByteArrayDataSource dataSource = new ByteArrayDataSource(pdfBytes, "application/pdf");
                attachmentPart.setDataHandler(new jakarta.activation.DataHandler(dataSource));
                attachmentPart.setFileName(fileName);

                // Combine elements into multipart shell
                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(textPart);
                multipart.addBodyPart(attachmentPart);
                message.setContent(multipart);

                LOGGER.info("Dispatching async message transport to destination: " + recipientEmail);
                Transport.send(message);
                LOGGER.info("Dynamic delivery confirmation completed successfully to " + recipientEmail);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed transactional delivery track execution run for " + recipientEmail, e);
            }
        }).start();
    }
}