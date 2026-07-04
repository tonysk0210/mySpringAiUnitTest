package com.example.myspringaiunittest.exception;

public class InvalidAnswerException extends RuntimeException {

    public InvalidAnswerException(String question, String answer) {
        super("回答驗證失敗：問題「" + question + "」的回答「" + answer + "」事實不正確。");
    }
}
