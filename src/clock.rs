//! Injectable clock for as-of processing and crash/replay tests.

use crate::model::Date;

pub trait Clock {
    fn now(&self) -> Date;
}

impl<T: Clock + ?Sized> Clock for &T {
    fn now(&self) -> Date {
        (**self).now()
    }
}

impl<T: Clock + ?Sized> Clock for &mut T {
    fn now(&self) -> Date {
        (**self).now()
    }
}

pub struct FixedClock {
    pub today: Date,
}

impl Clock for FixedClock {
    fn now(&self) -> Date {
        self.today
    }
}

pub struct MutableClock {
    pub today: Date,
}

impl MutableClock {
    pub fn new(start: Date) -> Self {
        MutableClock { today: start }
    }

    pub fn advance(&mut self, days: i64) {
        self.today = Date(self.today.0 + days);
    }
}

impl Clock for MutableClock {
    fn now(&self) -> Date {
        self.today
    }
}
