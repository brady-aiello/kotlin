/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "SingleThreadMarkAndSweep.hpp"

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "../GlobalData.hpp"
#include "../ObjectOps.hpp"
#include "../TestSupport.hpp"
#include "FinalizerHooksTestSupport.hpp"
#include "ObjectTestSupport.hpp"

using namespace kotlin;

// These tests can only work if `GC` is `SingleThreadMarkAndSweep`.
// TODO: Extracting GC into a separate module will help with this.

namespace {

struct Payload {
    ObjHeader* field1;
    ObjHeader* field2;
    ObjHeader* field3;

    static constexpr std::array kFields = {
            &Payload::field1,
            &Payload::field2,
            &Payload::field3,
    };
};

test_support::TypeInfoHolder typeHolder{test_support::TypeInfoHolder::ObjectBuilder<Payload>()};
test_support::TypeInfoHolder typeHolderWithFinalizer{test_support::TypeInfoHolder::ObjectBuilder<Payload>().addFlag(TF_HAS_FINALIZER)};

// TODO: Clean GlobalObjectHolder after it's gone.
class GlobalObjectHolder : private Pinned {
public:
    explicit GlobalObjectHolder(mm::ThreadData& threadData) {
        mm::GlobalsRegistry::Instance().RegisterStorageForGlobal(&threadData, &location_);
        mm::AllocateObject(&threadData, typeHolder.typeInfo(), &location_);
    }

    ObjHeader* header() { return location_; }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(location_); }
    test_support::Object<Payload>& operator->() { return test_support::Object<Payload>::FromObjHeader(location_); }

private:
    ObjHeader* location_;
};

// TODO: Clean GlobalObjectArrayHolder after it's gone.
class GlobalObjectArrayHolder : private Pinned {
public:
    explicit GlobalObjectArrayHolder(mm::ThreadData& threadData) {
        mm::GlobalsRegistry::Instance().RegisterStorageForGlobal(&threadData, &location_);
        mm::AllocateArray(&threadData, theArrayTypeInfo, 3, &location_);
    }

    ObjHeader* header() { return location_; }

    test_support::ObjectArray<3>& operator*() { return test_support::ObjectArray<3>::FromArrayHeader(location_->array()); }
    test_support::ObjectArray<3>& operator->() { return test_support::ObjectArray<3>::FromArrayHeader(location_->array()); }

    ObjHeader*& operator[](size_t index) noexcept { return (**this).elements()[index]; }

private:
    ObjHeader* location_;
};

// TODO: Clean GlobalCharArrayHolder after it's gone.
class GlobalCharArrayHolder : private Pinned {
public:
    explicit GlobalCharArrayHolder(mm::ThreadData& threadData) {
        mm::GlobalsRegistry::Instance().RegisterStorageForGlobal(&threadData, &location_);
        mm::AllocateArray(&threadData, theCharArrayTypeInfo, 3, &location_);
    }

    ObjHeader* header() { return location_; }

    test_support::CharArray<3>& operator*() { return test_support::CharArray<3>::FromArrayHeader(location_->array()); }
    test_support::CharArray<3>& operator->() { return test_support::CharArray<3>::FromArrayHeader(location_->array()); }

private:
    ObjHeader* location_;
};

class StackObjectHolder : private Pinned {
public:
    explicit StackObjectHolder(mm::ThreadData& threadData) { mm::AllocateObject(&threadData, typeHolder.typeInfo(), holder_.slot()); }

    ObjHeader* header() { return holder_.obj(); }

    test_support::Object<Payload>& operator*() { return test_support::Object<Payload>::FromObjHeader(holder_.obj()); }
    test_support::Object<Payload>& operator->() { return test_support::Object<Payload>::FromObjHeader(holder_.obj()); }

private:
    ObjHolder holder_;
};

class StackObjectArrayHolder : private Pinned {
public:
    explicit StackObjectArrayHolder(mm::ThreadData& threadData) { mm::AllocateArray(&threadData, theArrayTypeInfo, 3, holder_.slot()); }

    ObjHeader* header() { return holder_.obj(); }

    test_support::ObjectArray<3>& operator*() { return test_support::ObjectArray<3>::FromArrayHeader(holder_.obj()->array()); }
    test_support::ObjectArray<3>& operator->() { return test_support::ObjectArray<3>::FromArrayHeader(holder_.obj()->array()); }

    ObjHeader*& operator[](size_t index) noexcept { return (**this).elements()[index]; }

private:
    ObjHolder holder_;
};

class StackCharArrayHolder : private Pinned {
public:
    explicit StackCharArrayHolder(mm::ThreadData& threadData) { mm::AllocateArray(&threadData, theCharArrayTypeInfo, 3, holder_.slot()); }

    ObjHeader* header() { return holder_.obj(); }

    test_support::CharArray<3>& operator*() { return test_support::CharArray<3>::FromArrayHeader(holder_.obj()->array()); }
    test_support::CharArray<3>& operator->() { return test_support::CharArray<3>::FromArrayHeader(holder_.obj()->array()); }

private:
    ObjHolder holder_;
};

test_support::Object<Payload>& AllocateObject(mm::ThreadData& threadData) {
    ObjHolder holder;
    mm::AllocateObject(&threadData, typeHolder.typeInfo(), holder.slot());
    return test_support::Object<Payload>::FromObjHeader(holder.obj());
}

test_support::Object<Payload>& AllocateObjectWithFinalizer(mm::ThreadData& threadData) {
    ObjHolder holder;
    mm::AllocateObject(&threadData, typeHolderWithFinalizer.typeInfo(), holder.slot());
    return test_support::Object<Payload>::FromObjHeader(holder.obj());
}

KStdVector<ObjHeader*> Alive(mm::ThreadData& threadData) {
    KStdVector<ObjHeader*> objects;
    for (auto node : threadData.objectFactoryThreadQueue()) {
        objects.push_back(node.IsArray() ? node.GetArrayHeader()->obj() : node.GetObjHeader());
    }
    for (auto node : mm::GlobalData::Instance().objectFactory().Iter()) {
        objects.push_back(node.IsArray() ? node.GetArrayHeader()->obj() : node.GetObjHeader());
    }
    return objects;
}

using Color = mm::SingleThreadMarkAndSweep::ObjectData::Color;

Color GetColor(ObjHeader* objHeader) {
    auto nodeRef = mm::ObjectFactory<mm::SingleThreadMarkAndSweep>::NodeRef::From(objHeader);
    return nodeRef.GCObjectData().color();
}

class SingleThreadMarkAndSweepTest : public testing::Test {
public:
    ~SingleThreadMarkAndSweepTest() {
        mm::GlobalsRegistry::Instance().ClearForTests();
        mm::GlobalData::Instance().objectFactory().ClearForTests();
    }

    testing::MockFunction<void(ObjHeader*)>& finalizerHook() { return finalizerHooks_.finalizerHook(); }

private:
    FinalizerHooksTestSupport finalizerHooks_;
};

} // namespace

TEST_F(SingleThreadMarkAndSweepTest, RootSet) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global1{threadData};
        GlobalObjectArrayHolder global2{threadData};
        GlobalCharArrayHolder global3{threadData};
        StackObjectHolder stack1{threadData};
        StackObjectArrayHolder stack2{threadData};
        StackCharArrayHolder stack3{threadData};

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), global3.header(), stack1.header(), stack2.header(), stack3.header()));
        ASSERT_THAT(GetColor(global1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(global2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(global3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack3.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), global3.header(), stack1.header(), stack2.header(), stack3.header()));
        EXPECT_THAT(GetColor(global1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(global2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(global3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack3.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, InterconnectedRootSet) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global1{threadData};
        GlobalObjectArrayHolder global2{threadData};
        GlobalCharArrayHolder global3{threadData};
        StackObjectHolder stack1{threadData};
        StackObjectArrayHolder stack2{threadData};
        StackCharArrayHolder stack3{threadData};

        global1->field1 = stack1.header();
        global1->field2 = global1.header();
        global1->field3 = global2.header();
        global2[0] = global1.header();
        global2[1] = global3.header();
        stack1->field1 = global1.header();
        stack1->field2 = stack1.header();
        stack1->field3 = stack2.header();
        stack2[0] = stack1.header();
        stack2[1] = stack3.header();

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), global3.header(), stack1.header(), stack2.header(), stack3.header()));
        ASSERT_THAT(GetColor(global1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(global2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(global3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack3.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global1.header(), global2.header(), global3.header(), stack1.header(), stack2.header(), stack3.header()));
        EXPECT_THAT(GetColor(global1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(global2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(global3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack3.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, FreeObjects) {
    RunInNewThread([](mm::ThreadData& threadData) {
        auto& object1 = AllocateObject(threadData);
        auto& object2 = AllocateObject(threadData);

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(object1.header(), object2.header()));
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre());
    });
}

TEST_F(SingleThreadMarkAndSweepTest, FreeObjectsWithFinalizers) {
    RunInNewThread([this](mm::ThreadData& threadData) {
        auto& object1 = AllocateObjectWithFinalizer(threadData);
        auto& object2 = AllocateObjectWithFinalizer(threadData);

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(object1.header(), object2.header()));
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);

        EXPECT_CALL(finalizerHook(), Call(object1.header()));
        EXPECT_CALL(finalizerHook(), Call(object2.header()));
        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre());
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectReferencedFromRootSet) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global{threadData};
        StackObjectHolder stack{threadData};
        auto& object1 = AllocateObject(threadData);
        auto& object2 = AllocateObject(threadData);
        auto& object3 = AllocateObject(threadData);
        auto& object4 = AllocateObject(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
        ASSERT_THAT(GetColor(global.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object4.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
        EXPECT_THAT(GetColor(global.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object4.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsWithCycles) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global{threadData};
        StackObjectHolder stack{threadData};
        auto& object1 = AllocateObject(threadData);
        auto& object2 = AllocateObject(threadData);
        auto& object3 = AllocateObject(threadData);
        auto& object4 = AllocateObject(threadData);
        auto& object5 = AllocateObject(threadData);
        auto& object6 = AllocateObject(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        object2->field1 = object1.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();
        object4->field1 = object3.header();
        object5->field1 = object6.header();
        object6->field1 = object5.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header(),
                        object5.header(), object6.header()));
        ASSERT_THAT(GetColor(global.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object4.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object5.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object6.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
        EXPECT_THAT(GetColor(global.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object4.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsWithCyclesAndFinalizers) {
    RunInNewThread([this](mm::ThreadData& threadData) {
        GlobalObjectHolder global{threadData};
        StackObjectHolder stack{threadData};
        auto& object1 = AllocateObjectWithFinalizer(threadData);
        auto& object2 = AllocateObjectWithFinalizer(threadData);
        auto& object3 = AllocateObjectWithFinalizer(threadData);
        auto& object4 = AllocateObjectWithFinalizer(threadData);
        auto& object5 = AllocateObjectWithFinalizer(threadData);
        auto& object6 = AllocateObjectWithFinalizer(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        object2->field1 = object1.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();
        object4->field1 = object3.header();
        object5->field1 = object6.header();
        object6->field1 = object5.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header(),
                        object5.header(), object6.header()));
        ASSERT_THAT(GetColor(global.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object4.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object5.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object6.header()), Color::kWhite);

        EXPECT_CALL(finalizerHook(), Call(object5.header()));
        EXPECT_CALL(finalizerHook(), Call(object6.header()));
        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
        EXPECT_THAT(GetColor(global.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object4.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsWithCyclesIntoRootSet) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global{threadData};
        StackObjectHolder stack{threadData};
        auto& object1 = AllocateObject(threadData);
        auto& object2 = AllocateObject(threadData);

        global->field1 = object1.header();
        object1->field1 = global.header();
        stack->field1 = object2.header();
        object2->field1 = stack.header();

        ASSERT_THAT(Alive(threadData), testing::UnorderedElementsAre(global.header(), stack.header(), object1.header(), object2.header()));
        ASSERT_THAT(GetColor(global.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);

        threadData.gc().PerformFullGC();

        EXPECT_THAT(Alive(threadData), testing::UnorderedElementsAre(global.header(), stack.header(), object1.header(), object2.header()));
        EXPECT_THAT(GetColor(global.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object2.header()), Color::kWhite);
    });
}

TEST_F(SingleThreadMarkAndSweepTest, ObjectsRunGCTwice) {
    RunInNewThread([](mm::ThreadData& threadData) {
        GlobalObjectHolder global{threadData};
        StackObjectHolder stack{threadData};
        auto& object1 = AllocateObject(threadData);
        auto& object2 = AllocateObject(threadData);
        auto& object3 = AllocateObject(threadData);
        auto& object4 = AllocateObject(threadData);
        auto& object5 = AllocateObject(threadData);
        auto& object6 = AllocateObject(threadData);

        global->field1 = object1.header();
        object1->field1 = object2.header();
        object2->field1 = object1.header();
        stack->field1 = object3.header();
        object3->field1 = object4.header();
        object4->field1 = object3.header();
        object5->field1 = object6.header();
        object6->field1 = object5.header();

        ASSERT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header(),
                        object5.header(), object6.header()));
        ASSERT_THAT(GetColor(global.header()), Color::kWhite);
        ASSERT_THAT(GetColor(stack.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object1.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object2.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object3.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object4.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object5.header()), Color::kWhite);
        ASSERT_THAT(GetColor(object6.header()), Color::kWhite);

        threadData.gc().PerformFullGC();
        threadData.gc().PerformFullGC();

        EXPECT_THAT(
                Alive(threadData),
                testing::UnorderedElementsAre(
                        global.header(), stack.header(), object1.header(), object2.header(), object3.header(), object4.header()));
        EXPECT_THAT(GetColor(global.header()), Color::kWhite);
        EXPECT_THAT(GetColor(stack.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object1.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object2.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object3.header()), Color::kWhite);
        EXPECT_THAT(GetColor(object4.header()), Color::kWhite);
    });
}

// TODO: Weaks
// TODO: Permanents?
