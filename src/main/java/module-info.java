module org.example.p2psharefile {
    requires javafx.controls;
    requires javafx.fxml;
    
    // Bouncy Castle for TLS certificate generation
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;

    // Mở package cho JavaFX để có thể sử dụng reflection
    opens org.example.p2psharefile to javafx.fxml;
    opens org.example.p2psharefile.controller to javafx.fxml;
    opens org.example.p2psharefile.model to javafx.fxml;
    
    // Export các package cần thiết
    exports org.example.p2psharefile;
    exports org.example.p2psharefile.controller;
    exports org.example.p2psharefile.model;
    exports org.example.p2psharefile.service;
    exports org.example.p2psharefile.network;
    exports org.example.p2psharefile.security;
    exports org.example.p2psharefile.compression;
}