package com.loresentry.gateway.client.content;

/** The request kind determines which response shape is decoded. */
public record FileCreated(ContentData.Document document, ContentData.Episode episode) {}
