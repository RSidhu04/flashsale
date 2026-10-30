package com.ravan.flashsale.inventory;

/** A compare-and-set conflict, not a sold-out result. Roll back before retrying. */
public class InventoryConflictException extends RuntimeException {}
