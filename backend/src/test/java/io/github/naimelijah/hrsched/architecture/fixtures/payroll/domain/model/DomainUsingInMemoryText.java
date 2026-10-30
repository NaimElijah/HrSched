package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.CharArrayWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;

/**
 * Keeps the I/O rule: a value naming where something is, and readers, writers and streams over
 * memory, none of which reaches outside the process.
 */
public record DomainUsingInMemoryText(
    URI location,
    StringReader text,
    StringWriter written,
    ByteArrayInputStream bytes,
    ByteArrayOutputStream writtenBytes,
    CharArrayReader characters,
    CharArrayWriter writtenCharacters) {}
