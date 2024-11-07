package com.mkyong.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.text.html.Option;
import javax.swing.text.html.parser.Entity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.mkyong.model.SessionEntity;
import com.mkyong.model.TransactionEntity;
import com.mkyong.model.UserEntity;
import com.mkyong.model.UserEntity.UserRole;
import com.mkyong.model.UserEntity;
import com.mkyong.model.dtos.FundAccountDto;
import com.mkyong.model.dtos.Transaction.MakePaymentDto;
import com.mkyong.model.dtos.Transaction.PaymentErrorDto;
import com.mkyong.model.dtos.Transaction.PaymentResponseDto;
import com.mkyong.model.dtos.Transaction.PaymentSuccessDto;
import com.mkyong.model.dtos.Transaction.TxDto;
import com.mkyong.model.enums.TxStatus;
import com.mkyong.model.enums.TxType;
import com.mkyong.repository.SessionRepository;
import com.mkyong.repository.TransactionRepository;
import com.mkyong.repository.UserRepository;
import com.mkyong.responses.FundAccountResponse;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transaction;
import jakarta.transaction.Transactional;

@Service
public class TransactionService {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private FirebaseService firebaseService;
    @Autowired
    private SessionRepository sessionRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private UserRepository userRepository;

    @Modifying
    public PaymentResponseDto testingPay(MakePaymentDto data) {

        String txId = TransactionEntity.createId(data.getSenderId(),
                data.getReceiverId());
        try {
            // transactionRepository.makePayment("asasas");
            // transactionRepository.testingPay();
            entityManager.createNativeQuery(TransactionRepository.queryyy).getResultList();
            return new PaymentResponseDto(txId, TxStatus.success);
        } catch (Exception e) {
            return new PaymentResponseDto(txId, TxStatus.failed);

        }
    }

    private boolean checkDuplicateTransaction(MakePaymentDto dto) {
        LocalDateTime timeAgo = LocalDateTime.now().minusMinutes(5);
        Optional<TransactionEntity> duplicateExists = transactionRepository.checkRecentTx(dto.getSenderId(),
                dto.getReceiverId(),
                timeAgo);
        if (duplicateExists.isPresent()) {
            return true;
        } else {
            return false;
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public PaymentResponseDto makePayment(MakePaymentDto dto) {
        Optional<UserEntity> recepient = userRepository.findById(dto.getReceiverId());
        Optional<UserEntity> sender = userRepository.findById(dto.getSenderId());
        if (recepient.isEmpty() || sender.isEmpty()) {
            System.out.println("user not found!");
            return new PaymentResponseDto(null, TxStatus.failed, "user not found", HttpStatus.NOT_FOUND);
        }
        return transferMoney(dto);
    }

    // @org.springframework.transaction.annotation.Transactional
    // public PaymentResponseDto payDriver(MakePaymentDto dto) {

    // // PaymentResponseDto paymentResponseDto = new PaymentResponseDto();
    // return transferMoney(dto);

    // }

    private PaymentResponseDto transferMoney(MakePaymentDto dto) {
        if (!(dto.isIgnoreDuplicateTx())) {
            // return new PaymentResponseDto(null, null, "possible duplicate transaction",
            // HttpStatus.CONFLICT);
            boolean duplicateExists = checkDuplicateTransaction(dto);
            if (duplicateExists) {
                System.out.println("duplicate found, returning...");
                return new PaymentResponseDto(null, null, "possible duplicate transaction",
                        HttpStatus.CONFLICT);
            }
        }
        String txId = TransactionEntity.createId(dto.getSenderId(), dto.getReceiverId());
        Optional<UserEntity> sender_ = userRepository.findById(dto.getSenderId());
        UserEntity sender = sender_.get();
        BigDecimal senderBalance = sender.getBalance();

        Optional<UserEntity> receiver_ = userRepository.findById(dto.getReceiverId());
        UserEntity receiver = receiver_.get();
        BigDecimal receiverBalance = receiver.getBalance();
        if (senderBalance.compareTo(dto.getAmount()) >= 0) {
            System.out.println("user found!");

            try {
                sender.setBalance(sender.getBalance().subtract(dto.getAmount()));
                receiver.setBalance(receiver.getBalance().add(dto.getAmount()));
                TransactionEntity tx = new TransactionEntity(txId, dto.getTitle(),
                        dto.getSenderId(),
                        dto.getReceiverId(),
                        dto.getAmount(), TxStatus.success, TxType.ridePayment);
                transactionRepository.save(tx);
                userRepository.saveAll(List.of(sender, receiver));
                notifyRecepient(dto.getSenderId(), dto.getReceiverId(), dto.getAmount());
                return new PaymentResponseDto(txId, TxStatus.success);
            } catch (Exception e) {
                System.err.println(e);
                System.out.println("Error Making payment!, e is " + e.toString());
                // TODO: handle exception
                TransactionEntity tx = new TransactionEntity(txId, dto.getTitle(),
                        dto.getSenderId(),
                        dto.getReceiverId(),
                        dto.getAmount(), TxStatus.failed, TxType.ridePayment);
                transactionRepository.save(tx);
                return new PaymentResponseDto(txId, TxStatus.failed, e.getMessage());
            }

        } else {
            System.out.println("Insufficient funds!");
            return new PaymentResponseDto(txId, TxStatus.failed, "Insufficient Funds");
        }

    }

    public void notifyRecepient(String senderId, String receiverId, BigDecimal amount) {
        Optional<String> senderName = userRepository.getNameById(senderId);
        System.out.println("sender name is " + senderName.get());
        ArrayList<SessionEntity> sessionEntities = sessionRepository.findByUserId(receiverId);
        System.out.println("sessionsEntities size is  " + sessionEntities.size());
        ArrayList<String> fcmTokens = new ArrayList<>(3);
        for (int i = 0; i < sessionEntities.size(); i++) {
            String fcmToken = sessionEntities.get(i).getFcmToken();
            if ((fcmToken != null) && (fcmToken.length() != 0)) {
                fcmTokens.add(fcmToken);
            }
        }
        String body = "New payment from " + senderName.get();
        System.out.println("fcm token list is " + fcmTokens.toString());
        if (fcmTokens.size() <= 0) {
            return;
        }
        System.out.println("fcm tokens list length is " + fcmTokens.size());
        firebaseService.notifyTokens(fcmTokens, "Payment ₦" + amount, body);
    }

    public void notifyRecepientFunding(String receiverId, BigDecimal amount) {
        ArrayList<SessionEntity> sessionEntities = sessionRepository.findByUserId(receiverId);
        System.out.println("sessionsEntities size is  " + sessionEntities.size());
        ArrayList<String> fcmTokens = new ArrayList<>(3);
        for (int i = 0; i < sessionEntities.size(); i++) {
            String fcmToken = sessionEntities.get(i).getFcmToken();
            if ((fcmToken != null) && (fcmToken.length() != 0)) {
                fcmTokens.add(fcmToken);
            }
        }
        System.out.println("fcm token list is " + fcmTokens.toString());
        if (fcmTokens.size() <= 0) {
            return;
        }
        System.out.println("fcm tokens list length is " + fcmTokens.size());
        firebaseService.notifyTokens(fcmTokens, "Account Funded", "₦" + amount);
    }

    public Optional<TransactionEntity> fetchTx(String txId) {
        return transactionRepository.findById(txId);

    }

    @org.springframework.transaction.annotation.Transactional
    public FundAccountResponse fundUserAccount(FundAccountDto dto) {
        Optional<UserEntity> userEntity = userRepository.findById(dto.getUserId());
        if (userEntity.isEmpty()) {
            return new FundAccountResponse("user does not exist", HttpStatus.NOT_FOUND);
        }
        if (userEntity.get().getRole() == UserRole.driver) {
            return new FundAccountResponse("Driver accounts cannot be funded", HttpStatus.FORBIDDEN);
        }
        try {
            userEntity.get().setBalance(userEntity.get().getBalance().add(dto.getAmount()));
            userRepository.save(userEntity.get());
            notifyRecepientFunding(dto.getUserId(), dto.getAmount());
            return new FundAccountResponse("success");
        } catch (Exception e) {
            // TODO: handle exception

            return new FundAccountResponse("error funding account", HttpStatus.INTERNAL_SERVER_ERROR);

        }
    }

    public Object[] fetchTxByUserId(String userId, int limit1, int limit2) {
        System.out.println("user Id is " + userId);
        Slice<TransactionEntity> txList_ = transactionRepository.findBySenderIdOrReceiverId(userId, userId,
                PageRequest.of(limit1, limit2));
        System.out.println("tx list is " + txList_.getContent().toArray().toString());
        return txList_.getContent().toArray();
    }

    // public PaymentResponseDto payDriver2(MakePaymentDto data) {

    // // PaymentResponseDto paymentResponseDto = new PaymentResponseDto();
    // try {
    // // int res =
    // transactionRepository.makePayment("1-2-17149979921");
    // // System.out.println("query response is" + res);
    // paymentResponseDto.setAmount(null);
    // paymentResponseDto.setSenderId(null);
    // paymentResponseDto.setTxId(null);
    // paymentResponseDto.setReveiverId(null);
    // return new Pay;
    // } catch (Exception e) {
    // paymentResponseDto.setAmount(null);
    // paymentResponseDto.setErrorCode(0);
    // paymentResponseDto.setErrorMsg(e.toString());
    // return paymentResponseDto;

    // // TODO: handle exception
    // }

    // // return paymentResponseDto;
    // }

}