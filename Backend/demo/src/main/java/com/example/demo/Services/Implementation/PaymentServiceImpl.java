package com.example.demo.Services.Implementation;

import com.example.demo.Entities.Payment;
import com.example.demo.Enums.PaymentMode;
import com.example.demo.Enums.PaymentStatus;
import com.example.demo.Payloads.PaymentDto;
import com.example.demo.PaymentDueReminder.Reminder.MonthlyBill;
import com.example.demo.PaymentDueReminder.Reminder.MonthlyBillRepository;
import com.example.demo.Repositories.PaymentRepository;
import com.example.demo.Repositories.SocietyAdminRepository;
import com.example.demo.Repositories.SocietyRepo;
import com.example.demo.Repositories.UserRepository;
import com.example.demo.Services.PaymentService;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocietyRepo societyRepo;

    @Autowired
    private SocietyAdminRepository societyAdminRepository;

    @Autowired
    private ModelMapper modelMapper;


/*============================================================================*/

    private final RazorpayClient razorpayClient;
    private final PaymentRepository paymentRepository;
    private final MonthlyBillRepository billRepo;


    @Value("${razorpay.secret}")
    private String razorpaySecret;

    public PaymentServiceImpl(
            RazorpayClient razorpayClient,
            PaymentRepository paymentRepo,
            MonthlyBillRepository billRepo
    ) {
        this.razorpayClient = razorpayClient;
        this.paymentRepository = paymentRepo;
        this.billRepo = billRepo;
    }


    /* ================= CREATE ORDER ================= */

    @Override
    public Map<String, Object> createOrder(PaymentDto dto) {

        MonthlyBill bill = billRepo.findById(dto.getBillId())
                .orElseThrow(() -> new RuntimeException("Bill not found"));

        double amountToPay = bill.getTotalAmount() - bill.getPaidAmount();

        if (amountToPay <= 0) {
            throw new RuntimeException("Bill already paid");
        }

        try {
            // 1️⃣ Save Payment (PENDING)
            Payment payment = new Payment();
            payment.setBillId(bill.getId());
            payment.setAmount(amountToPay);
            payment.setPaymentMode(
                    PaymentMode.valueOf(dto.getPaymentMode().toUpperCase())
            );
            payment.setStatus(PaymentStatus.PENDING);
            payment.setProvider("RAZORPAY");
            payment.setCreatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            // 2️⃣ Razorpay Order create
            JSONObject orderReq = new JSONObject();
            orderReq.put("amount", (int) (amountToPay * 100));
            orderReq.put("currency", "INR");
            orderReq.put("receipt", "bill_" + bill.getId());

            com.razorpay.Order razorpayOrder =
                    razorpayClient.orders.create(orderReq);

            // 3️⃣ Save Razorpay orderId
            payment.setOrderId(razorpayOrder.get("id"));
            paymentRepository.save(payment);

            // 4️⃣ Send to frontend
            return Map.of(
                    "orderId", razorpayOrder.get("id"),
                    "amount", amountToPay
            );

        } catch (Exception e) {
            e.printStackTrace();   // 🔥 THIS LINE
            throw new RuntimeException("Order creation failed", e);
        }
    }

    /* ================= VERIFY PAYMENT ================= */

    @Override
    public void verifyPayment(PaymentDto dto) {

        try {
            Payment payment = paymentRepository
                    .findByOrderId(dto.getRazorpayOrderId())
                    .orElseThrow(() -> new RuntimeException("Payment not found"));

            // 1️⃣ Verify signature
            String data = dto.getRazorpayOrderId()
                    + "|" + dto.getRazorpayPaymentId();

            String generatedSignature =
                    Utils.getHash(data, razorpaySecret);

            if (!generatedSignature.equals(dto.getRazorpaySignature())) {
                payment.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(payment);
                throw new RuntimeException("Invalid payment signature");
            }

            // 2️⃣ Payment SUCCESS
            payment.setPaymentId(dto.getRazorpayPaymentId());
            payment.setSignature(dto.getRazorpaySignature());
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setPaymentDate(LocalDateTime.now());
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            // 3️⃣ Update Monthly Bill
            MonthlyBill bill =
                    billRepo.findById(payment.getBillId()).orElseThrow();

            bill.setPaidAmount(bill.getPaidAmount() + payment.getAmount());

            if (bill.getPaidAmount() >= bill.getTotalAmount()) {
                bill.setStatus(PaymentStatus.COMPLETED); // ✅ CORRECT
            }

            billRepo.save(bill);

        } catch (Exception e) {
            throw new RuntimeException("Payment verification failed", e);
        }
    }


/*============================================================================*/

// CREATE PAYMENT
@Override
public PaymentDto createPayment(PaymentDto dto) {

    Payment payment = modelMapper.map(dto, Payment.class);
    payment.setStatus(PaymentStatus.PENDING);
    payment.setPaymentDate(LocalDateTime.now());
    payment.setCreatedAt(LocalDateTime.now());
    payment.setUpdatedAt(LocalDateTime.now());

    // 🔥 SET payerName
    if (dto.getPaidById() != null && dto.getPaidByRole() != null) {
        switch (dto.getPaidByRole()) {
            case "SOCIETY_ADMIN":
                societyAdminRepository.findById(dto.getPaidById())
                        .ifPresent(admin -> payment.setPayerName(admin.getAdminName()));
                break;
            case "SUPER_ADMIN":
            case "TENANT":
            case "STAFF":
            case "OWNER":
            default:
                userRepository.findById(dto.getPaidById())
                        .ifPresent(u -> payment.setPayerName(u.getName()));
                break;
        }
    }

    // 🔥 SET societyName
    if (dto.getSocietyId() != null) {
        societyRepo.findById(dto.getSocietyId())
                .ifPresent(s -> payment.setSocietyName(s.getName()));
    }

    Payment saved = paymentRepository.save(payment);
    return mapToDto(saved);
}


    @Override
    public List<PaymentDto> getPaymentsForUser(Integer userId, String role, Integer societyId) {

        List<Payment> payments;

        switch (role) {
            case "SUPER_ADMIN":
                // See all SocietyAdmin payments
                payments = paymentRepository.findByReceivedByRoleOrderByPaymentDateDesc("SUPER_ADMIN");
                break;

            case "SOCIETY_ADMIN":
                // See payments of own society
                payments = paymentRepository.findBySocietyIdOrderByPaymentDateDesc(societyId);
                break;

            default:
                // STAFF / TENANT / OWNER → see their own payments
                payments = paymentRepository.findByPaidByIdOrderByPaymentDateDesc(userId);
                break;
        }

        return payments.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }



    @Override
    public PaymentDto getPaymentById(Integer paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return mapToDto(payment);
    }



    @Override
    public PaymentDto updatePaymentStatus(
            Integer paymentId,
            PaymentStatus status,
            Integer userId,
            String role,
            Integer societyId
    ) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        // 🔐 AUTHORIZATION RULES

        // 1️⃣ SocietyAdmin → SuperAdmin payment
        if ("SUPER_ADMIN".equals(role)
                && "SUPER_ADMIN".equals(payment.getReceivedByRole())) {

            payment.setStatus(status);
        }

        // 2️⃣ Tenant / Staff / Owner → SocietyAdmin payment
        else if ("SOCIETY_ADMIN".equals(role)
                && "SOCIETY_ADMIN".equals(payment.getReceivedByRole())
                && payment.getSocietyId().equals(societyId)) {

            payment.setStatus(status);
        }

        else {
            throw new RuntimeException("Not authorized to update payment status");
        }

        payment.setUpdatedAt(LocalDateTime.now());
        Payment updated = paymentRepository.save(payment);

        return mapToDto(updated);
    }








    @Override
    public PaymentDto updatePayment(Integer paymentId, PaymentDto dto) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));

        // Update only allowed fields
        payment.setAmount(dto.getAmount());
        payment.setDescription(dto.getDescription());
        payment.setStatus(dto.getStatus());
        payment.setUpdatedAt(LocalDateTime.now());

        Payment updated = paymentRepository.save(payment);
        return mapToDto(updated);
    }



    @Override
    public boolean deletePayment(Integer paymentId, Integer userId, String role) {

        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) return false;

        // Only creator or SuperAdmin can delete
        if (payment.getPaidById().equals(userId) || "SUPER_ADMIN".equals(role)) {
            paymentRepository.delete(payment);
            return true;
        }

        return false;
    }

    // 🔹 Helper to map entity → DTO + fetch user/society names
    private PaymentDto mapToDto(Payment payment) {
        PaymentDto dto = modelMapper.map(payment, PaymentDto.class);

        // Paid By Name
        if(payment.getPaidById() != null) {
            userRepository.findById(payment.getPaidById())
                    .ifPresent(u -> dto.setPaidByName(u.getName()));
        }

        // Received By Name
        if(payment.getReceivedById() != null) {
            userRepository.findById(payment.getReceivedById())
                    .ifPresent(u -> dto.setReceivedByName(u.getName()));
        }

        // TODO: fetch societyName if needed
        return dto;
    }
}
